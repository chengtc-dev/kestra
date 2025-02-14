package io.kestra.webserver.controllers.api;

import io.kestra.core.models.QueryFilter;
import io.kestra.core.models.executions.LogEntry;
import io.kestra.core.repositories.LogRepositoryInterface;
import io.kestra.core.services.ExecutionLogService;
import io.kestra.core.tenant.TenantService;
import io.kestra.webserver.converters.QueryFilterFormat;
import io.kestra.webserver.responses.PagedResults;
import io.kestra.core.services.LogStreamingService;
import io.kestra.webserver.utils.PageableUtils;
import io.kestra.webserver.utils.QueryFilterUtils;
import io.kestra.webserver.utils.RequestUtils;
import io.kestra.webserver.utils.TimeLineSearch;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.format.Format;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Min;
import org.slf4j.event.Level;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.InputStream;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.kestra.core.utils.DateUtils.validateTimeline;


@Validated
@Controller("/api/v1/")
@Requires(beans = LogRepositoryInterface.class)
public class LogController {
    @Inject
    private LogRepositoryInterface logRepository;

    @Inject
    private ExecutionLogService logService;

    @Inject
    private TenantService tenantService;

    @Inject
    private LogStreamingService logStreamingService;

    @ExecuteOn(TaskExecutors.IO)
    @Get(uri = "logs/search")
    @Operation(tags = {"Logs"}, summary = "Search for logs")
    public PagedResults<LogEntry> find(
        @Parameter(description = "The current page") @QueryValue(defaultValue = "1") @Min(1) int page,
        @Parameter(description = "The current page size") @QueryValue(defaultValue = "10") @Min(1) int size,
        @Parameter(description = "The sort of current page") @Nullable @QueryValue List<String> sort,
        @Parameter(description = "Filters") @Nullable @QueryFilterFormat List<QueryFilter> filters,
        // Deprecated params
        @Parameter(description = "A string filter", deprecated = true) @Nullable @QueryValue(value = "q") String query,
        @Parameter(description = "A namespace filter prefix",deprecated = true) @Nullable @QueryValue String namespace,
        @Parameter(description = "A flow id filter", deprecated = true) @Nullable @QueryValue String flowId,
        @Parameter(description = "A trigger id filter",deprecated = true) @Nullable @QueryValue String triggerId,
        @Parameter(description = "The min log level filter", deprecated = true) @Nullable @QueryValue Level minLevel,
        @Parameter(description = "The start datetime", deprecated = true) @Nullable @Format("yyyy-MM-dd'T'HH:mm[:ss][.SSS][XXX]") @QueryValue ZonedDateTime startDate,
        @Parameter(description = "The end datetime", deprecated = true) @Nullable @Format("yyyy-MM-dd'T'HH:mm[:ss][.SSS][XXX]") @QueryValue ZonedDateTime endDate
    ) throws HttpStatusException {
        // If filters is empty, map old params to QueryFilter
        if (filters == null || filters.isEmpty()) {
            filters = RequestUtils.mapLegacyParamsToFilters(
                query,
                namespace,
                flowId,
                triggerId,
                minLevel,
                startDate,
                endDate,
                null,
                null,
                null,
                null,
                null,
                null);
        }
        final ZonedDateTime now = ZonedDateTime.now();

        TimeLineSearch timeLineSearch = TimeLineSearch.extractFrom(filters);
        validateTimeline(timeLineSearch.getStartDate(), timeLineSearch.getEndDate());

        ZonedDateTime resolvedStartDate = RequestUtils.resolveAbsoluteDateTime(timeLineSearch.getStartDate(),
            timeLineSearch.getTimeRange(),
            now);

        // Update filters with the resolved startDate
        filters = QueryFilterUtils.updateFilters(filters, resolvedStartDate);
        return PagedResults.of(logRepository.find(
            PageableUtils.from(page, size, sort),
            tenantService.resolveTenant(),
            filters
        ));
    }

    @ExecuteOn(TaskExecutors.IO)
    @Get(uri = "logs/{executionId}")
    @Operation(tags = {"Logs"}, summary = "Get logs for a specific execution, taskrun or task")
    public List<LogEntry> findByExecution(
        @Parameter(description = "The execution id") @PathVariable String executionId,
        @Parameter(description = "The min log level filter") @Nullable @QueryValue Level minLevel,
        @Parameter(description = "The taskrun id") @Nullable @QueryValue String taskRunId,
        @Parameter(description = "The task id") @Nullable @QueryValue String taskId,
        @Parameter(description = "The attempt number") @Nullable @QueryValue Integer attempt
    ) {
        return logService.getExecutionLogs(
            tenantService.resolveTenant(),
            executionId,
            minLevel,
            taskRunId,
            Optional.ofNullable(taskId).map(List::of).orElse(null),
            attempt,
            true
        );
    }

    @ExecuteOn(TaskExecutors.IO)
    @Get(uri = "logs/{executionId}/download", produces = MediaType.TEXT_PLAIN)
    @Operation(tags = {"Logs"}, summary = "Download logs for a specific execution, taskrun or task")
    public StreamedFile download(
        @Parameter(description = "The execution id") @PathVariable String executionId,
        @Parameter(description = "The min log level filter") @Nullable @QueryValue Level minLevel,
        @Parameter(description = "The taskrun id") @Nullable @QueryValue String taskRunId,
        @Parameter(description = "The task id") @Nullable @QueryValue String taskId,
        @Parameter(description = "The attempt number") @Nullable @QueryValue Integer attempt
    ) {
        InputStream inputStream = logService.getExecutionLogsAsStream(
            tenantService.resolveTenant(),
            executionId,
            minLevel,
            taskRunId,
            Optional.ofNullable(taskId).map(List::of).orElse(null),
            attempt,
            true
        );
        return new StreamedFile(inputStream, MediaType.TEXT_PLAIN_TYPE).attach(executionId + ".log");
    }

    @ExecuteOn(TaskExecutors.IO)
    @Get(uri = "logs/{executionId}/follow", produces = MediaType.TEXT_EVENT_STREAM)
    @Operation(tags = {"Logs"}, summary = "Follow logs for a specific execution")
    public Flux<Event<LogEntry>> follow(
        @Parameter(description = "The execution id") @PathVariable String executionId,
        @Parameter(description = "The min log level filter") @Nullable @QueryValue Level minLevel
    ) {
        String subscriberId = UUID.randomUUID().toString();
        final List<String> levels = LogEntry.findLevelsByMin(minLevel).stream().map(Enum::name).toList();

        return Flux.<Event<LogEntry>>create(emitter -> {
                // send a first "empty" event so the SSE is correctly initialized in the frontend in case there are no logs
                emitter.next(Event.of(LogEntry.builder().build()).id("start"));

                // fetch repository first
                logService.getExecutionLogs(tenantService.resolveTenant(), executionId, minLevel, List.of(), true)
                    .forEach(logEntry -> emitter.next(Event.of(logEntry).id("progress")));

                // consume in realtime
                logStreamingService.registerSubscriber(executionId, subscriberId, emitter, levels);
            }, FluxSink.OverflowStrategy.BUFFER)
            .doFinally(ignored -> logStreamingService.unregisterSubscriber(executionId, subscriberId));
    }

    @ExecuteOn(TaskExecutors.IO)
    @Delete(uri = "logs/{executionId}")
    @Operation(tags = {"Logs"}, summary = "Delete logs for a specific execution, taskrun or task")
    public void delete(
        @Parameter(description = "The execution id") @PathVariable String executionId,
        @Parameter(description = "The min log level filter") @Nullable @QueryValue Level minLevel,
        @Parameter(description = "The taskrun id") @Nullable @QueryValue String taskRunId,
        @Parameter(description = "The task id") @Nullable @QueryValue String taskId,
        @Parameter(description = "The attempt number") @Nullable @QueryValue Integer attempt
    ) {
        logRepository.deleteByQuery(tenantService.resolveTenant(), executionId, taskId, taskRunId, minLevel, attempt);
    }

    @ExecuteOn(TaskExecutors.IO)
    @Delete(uri = "logs/{namespace}/{flowId}")
    @Operation(tags = {"Logs"}, summary = "Delete logs for a specific execution, taskrun or task")
    public void deleteFromFlow(
        @Parameter(description = "The namespace") @PathVariable String namespace,
        @Parameter(description = "The flow identifier") @PathVariable String flowId,
        @Parameter(description = "The trigger id") @Nullable @QueryValue String triggerId
    ) {
        logRepository.deleteByQuery(tenantService.resolveTenant(), namespace, flowId, triggerId);
    }
}
