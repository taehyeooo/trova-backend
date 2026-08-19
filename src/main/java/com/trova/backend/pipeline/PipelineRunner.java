package com.trova.backend.pipeline;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class PipelineRunner {

    private final String scriptPath;
    private final String workDirBase;
    private final String geminiApiKey;

    public PipelineRunner(
            @Value("${app.pipeline.script-path}") String scriptPath,
            @Value("${app.pipeline.work-dir}") String workDirBase,
            @Value("${app.pipeline.gemini-api-key}") String geminiApiKey
    ) {
        this.scriptPath = scriptPath;
        this.workDirBase = workDirBase;
        this.geminiApiKey = geminiApiKey;
    }

    public List<ExtractedPlace> run(String url, Long jobId) {
        Path workDir = Path.of(workDirBase, "job-" + jobId);
        ProcessBuilder builder = new ProcessBuilder("python3", scriptPath, url, workDir.toString());
        builder.environment().put("GEMINI_API_KEY", geminiApiKey);

        try {
            Process process = builder.start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(5, TimeUnit.MINUTES);

            if (!finished) {
                process.destroyForcibly();
                throw new PipelineException("파이프라인 실행 시간 초과: " + url);
            }
            if (process.exitValue() != 0) {
                throw new PipelineException("파이프라인 실행 실패(exit=" + process.exitValue() + "): " + stderr);
            }
            return PipelineOutputParser.parse(stdout);
        } catch (IOException e) {
            throw new PipelineException("파이프라인 프로세스 시작 실패: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException("파이프라인 실행 중 인터럽트: " + e.getMessage(), e);
        }
    }
}
