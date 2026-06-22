package com.yizhaoqi.smartpai.paper.parser;

public record BoundingBox(
        Integer pageNumber,
        Double left,
        Double bottom,
        Double right,
        Double top,
        String unit,
        String coordinateSystem
) {
}
