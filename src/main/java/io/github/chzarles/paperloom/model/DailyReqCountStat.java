package io.github.chzarles.paperloom.model;

import java.time.LocalDate;

public record DailyReqCountStat(
        LocalDate recordDate,
        Long totalRequestCount
) {
}