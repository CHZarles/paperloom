package io.github.chzarles.paperloom.utils;

import io.github.chzarles.paperloom.exception.CustomException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 管理端查询参数的日期解析。接受 {@code 2026-07-27}、{@code 2026-07-27 10:30} 和完整 ISO 时间三种写法。
 */
public final class DateTimeUtils {

    private DateTimeUtils() {
    }

    public static LocalDateTime parseStartDate(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.trim().isEmpty()) {
            return null;
        }

        try {
            return LocalDateTime.parse(dateTimeStr);
        } catch (DateTimeParseException e1) {
            try {
                if (dateTimeStr.length() == 16) {
                    return LocalDateTime.parse(dateTimeStr + ":00");
                }

                if (dateTimeStr.length() == 13) {
                    return LocalDateTime.parse(dateTimeStr + ":00:00");
                }

                if (dateTimeStr.length() == 10) {
                    return LocalDate.parse(dateTimeStr).atStartOfDay();
                }
            } catch (Exception e2) {
                LogUtils.logBusinessError("PARSE_START_DATETIME", "system", "无法解析起始时间: %s", e2, dateTimeStr);
                throw new CustomException("无效的起始时间格式: " + dateTimeStr, HttpStatus.BAD_REQUEST);
            }
        }

        throw new CustomException("无效的起始时间格式: " + dateTimeStr, HttpStatus.BAD_REQUEST);
    }

    public static LocalDateTime parseEndDate(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.trim().isEmpty()) {
            return null;
        }

        try {
            return LocalDateTime.parse(dateTimeStr);
        } catch (DateTimeParseException e1) {
            try {
                if (dateTimeStr.length() == 16) {
                    return LocalDateTime.parse(dateTimeStr + ":59");
                }

                if (dateTimeStr.length() == 13) {
                    return LocalDateTime.parse(dateTimeStr + ":59:59");
                }

                if (dateTimeStr.length() == 10) {
                    return LocalDate.parse(dateTimeStr).plusDays(1).atStartOfDay().minusSeconds(1);
                }
            } catch (Exception e2) {
                LogUtils.logBusinessError("PARSE_END_DATETIME", "system", "无法解析结束时间: %s", e2, dateTimeStr);
                throw new CustomException("无效的结束时间格式: " + dateTimeStr, HttpStatus.BAD_REQUEST);
            }
        }

        throw new CustomException("无效的结束时间格式: " + dateTimeStr, HttpStatus.BAD_REQUEST);
    }
}
