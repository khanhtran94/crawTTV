package com.example.crawttv;

public class CrawlerConfig {
    private String bookName;
    private String slug;
    private int fromChapter;
    private int toChapter;
    private int nextChapter;
    private int batchSize;
    private long requestDelayMillis;
    private boolean enabled;

    public String getBookName() {
        return bookName;
    }

    public void setBookName(String bookName) {
        this.bookName = bookName;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public int getFromChapter() {
        return fromChapter;
    }

    public void setFromChapter(int fromChapter) {
        this.fromChapter = fromChapter;
    }

    public int getToChapter() {
        return toChapter;
    }

    public void setToChapter(int toChapter) {
        this.toChapter = toChapter;
    }

    public int getNextChapter() {
        return nextChapter;
    }

    public void setNextChapter(int nextChapter) {
        this.nextChapter = nextChapter;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getRequestDelayMillis() {
        return requestDelayMillis;
    }

    public void setRequestDelayMillis(long requestDelayMillis) {
        this.requestDelayMillis = requestDelayMillis;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
