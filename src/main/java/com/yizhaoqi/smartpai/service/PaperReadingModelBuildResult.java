package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperPage;
import com.yizhaoqi.smartpai.model.PaperReadingElement;
import com.yizhaoqi.smartpai.model.PaperSection;

import java.util.List;

public record PaperReadingModelBuildResult(
        List<PaperPage> pages,
        List<PaperSection> sections,
        List<PaperLocation> locations,
        List<PaperReadingElement> readingElements,
        String diagnosticsJson
) {
}
