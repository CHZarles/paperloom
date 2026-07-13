package io.github.chzarles.paperloom.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConfigurationProperties(prefix = "paperloom.trace")
public class ProductTraceProperties {

    private boolean enabled = true;
    private Path root = Path.of("data", "traces", "product-react");
    private int queueCapacity = 1000;
    private int writerThreads = 1;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Path getRoot() {
        return root;
    }

    public void setRoot(Path root) {
        this.root = root == null ? Path.of("data", "traces", "product-react") : root;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getWriterThreads() {
        return writerThreads;
    }

    public void setWriterThreads(int writerThreads) {
        this.writerThreads = writerThreads;
    }
}
