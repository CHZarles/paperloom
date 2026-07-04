package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperPage;

import java.util.List;

public record PaperReadingModelBuildResult(
        List<PaperPage> pages,
        List<PaperLocation> locations,
        String diagnosticsJson
) {
}
