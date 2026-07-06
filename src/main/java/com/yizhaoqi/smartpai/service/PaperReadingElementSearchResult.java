package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperLocationType;
import com.yizhaoqi.smartpai.model.PaperReadingElement;

public record PaperReadingElementSearchResult(
        PaperReadingElement element,
        String routedLocationRef,
        PaperLocationType routedLocationType,
        String routingSource
) {
}
