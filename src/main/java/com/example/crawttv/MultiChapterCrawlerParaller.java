package com.example.crawttv;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * Crawl nhiều chương truyện từ app Android thông qua ADB + UIAutomator.
 *
 * Cách dùng:
 * 1. Kết nối điện thoại bằng USB Debugging hoặc Wireless Debugging.
 * 2. Mở app tại chương muốn bắt đầu lấy.
 * 3. Chỉnh phần CẤU HÌNH bên dưới.
 * 4. Bấm Run class này trong IDE.
 *
 * Kết quả:
 * - Mỗi chương được lưu riêng trong thư mục stories/.
 * - Tất cả chương được ghép thêm vào stories/all_chapters.txt.
 */
public class MultiChapterCrawlerParaller {

    // =========================================================
    // CẤU HÌNH — CHỈNH PHẦN NÀY RỒI BẤM RUN
    // =========================================================

    /**
     * Nếu IDE nhận được lệnh adb thì giữ nguyên "adb".
     *
     * Nếu báo lỗi Cannot run program "adb", đổi thành đường dẫn đầy đủ, ví dụ:
     * C:\\Users\\Admin\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe
     */
    private static final String ADB_PATH = "adb";

    /**
     * Danh sách thiết bị muốn chạy song song.
     * Mỗi emulator cần đang mở sẵn app ở truyện/chương bắt đầu khác nhau.
     */
    private static final List<String> PARALLEL_DEVICE_SERIALS = List.of(
            "emulator-5554",
            "emulator-5556"
    );

    /**
     * Khi bấm Run trực tiếp: để rỗng để main tự chạy song song các thiết bị trên.
     * Khi muốn chạy riêng 1 máy: truyền VM option -Ddevice=emulator-5554.
     */
    private static final String DEVICE_SERIAL =
            System.getProperty("device", "");

    /**
     * Tên job/thư mục output. Khi chạy song song, main sẽ tự truyền -Dbook theo serial.
     */
    private static final String BOOK_NAME =
            System.getProperty(
                    "book",
                    DEVICE_SERIAL.isBlank() ? "parallel_parent" : DEVICE_SERIAL
            ).replaceAll("[^a-zA-Z0-9._-]+", "_");

    /** Số chương tối đa muốn lấy trong một lần chạy. */
    private static final int MAX_CHAPTERS = 100
            ;

    /**
     * Nếu chương trình không tìm được nút "Chương sau" trong XML:
     *
     * - Đặt tọa độ nút vào NEXT_BUTTON_X/Y; hoặc
     * - Giữ null để dùng thao tác vuốt sang trái.
     *
     * Ví dụ:
     * private static final Integer NEXT_BUTTON_X = 950;
     * private static final Integer NEXT_BUTTON_Y = 2150;
     */
    private static final Integer NEXT_BUTTON_X = null;
    private static final Integer NEXT_BUTTON_Y = null;

    /**
     * Khi không tìm được nút chương sau và không có tọa độ:
     * true  = vuốt để thử chuyển chương (xem SWIPE_MODE bên dưới).
     * false = dừng crawler.
     */
    private static final boolean USE_SWIPE_WHEN_NEXT_BUTTON_NOT_FOUND = true;

    /**
     * Kiểu vuốt khi không tìm được nút "Chương sau":
     *
     * HORIZONTAL = vuốt ngang (trái/phải) — dùng cho app đọc kiểu lật trang.
     * VERTICAL_SCROLL = vuốt dọc (lên nhiều lần) để cuộn hết nội dung chương
     *                    hiện tại rồi app tự tải chương kế tiếp — dùng cho
     *                    app đọc kiểu cuộn dọc liên tục (như Tàng Thư Viện).
     *
     * Nếu app của bạn là kiểu cuộn dọc để đọc hết chương rồi mới sang chương
     * mới, hãy đổi thành VERTICAL_SCROLL.
     */
    private static final SwipeMode SWIPE_MODE = SwipeMode.VERTICAL_SCROLL;

    /** Tọa độ vuốt ngang, dành cho màn hình 1080x2400. */
    private static final int SWIPE_START_X = 900;
    private static final int SWIPE_START_Y = 1200;
    private static final int SWIPE_END_X = 180;
    private static final int SWIPE_END_Y = 1200;
    private static final int SWIPE_DURATION_MS = 350;

    /**
     * Tọa độ vuốt dọc (để cuộn nội dung lên, đọc tiếp xuống dưới),
     * dành cho màn hình 1080x2400.
     */
    private static final int VERTICAL_SWIPE_X = 360;
    private static final int VERTICAL_SWIPE_START_Y = 1050;
    private static final int VERTICAL_SWIPE_END_Y = 280;
    private static final int VERTICAL_SWIPE_DURATION_MS = 450;

    /**
     * Số lần vuốt dọc liên tiếp mỗi lần thử chuyển chương.
     * App cuộn dọc thường cần nhiều lần vuốt mới hết một chương.
     */
    private static final int VERTICAL_SWIPE_REPEAT_COUNT = 8;

    /** Khoảng nghỉ giữa các lần vuốt dọc liên tiếp. */
    private static final long VERTICAL_SWIPE_INTERVAL_MS = 500;

    /** Bấm giữa màn hình để thử hiện thanh điều khiển đọc truyện. */
    private static final int SCREEN_CENTER_X = 10;
    private static final int SCREEN_CENTER_Y = 700;

    /** Chờ tối đa bao lâu để chương mới tải xong. */
    private static final long CHAPTER_CHANGE_TIMEOUT_MS = 20_000;

    /** Khoảng nghỉ giữa các lần kiểm tra nội dung. */
    private static final long POLL_INTERVAL_MS = 1_000;

    /**
     * Resource ID chứa toàn bộ nội dung chương.
     * File XML của app hiện tại cho thấy nội dung nằm tại tvContent.
     */
    private static final String CONTENT_RESOURCE_ID =
            "com.book.truyen.tangthuvien:id/tvContent";

    /** Thư mục kết quả riêng cho từng thiết bị/job. */
    private static final Path OUTPUT_DIRECTORY = Path.of("stories", BOOK_NAME);

    /** File XML tạm trên điện thoại. */
    private static final String REMOTE_XML_PATH = "/sdcard/window.xml";

    /** File XML tạm trên máy tính, tách riêng theo từng thiết bị/job. */
    private static final Path LOCAL_XML_PATH =
            Path.of("crawler-work", BOOK_NAME, "window.xml");

    /** File ghép tất cả chương. */
    private static final Path MERGED_OUTPUT_FILE =
            OUTPUT_DIRECTORY.resolve("all_chapters.txt");

    // =========================================================
    // KHÔNG CẦN CHỈNH PHẦN BÊN DƯỚI
    // =========================================================

    private enum SwipeMode {
        HORIZONTAL,
        VERTICAL_SCROLL
    }

    private static final Pattern CHAPTER_NUMBER_PATTERN =
            Pattern.compile("(?i)Chương\\s+(\\d+)");

    private static final Pattern BOUNDS_PATTERN =
            Pattern.compile("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]");

    private static final Pattern POSTER_PATTERN =
            Pattern.compile("(?m)^Người đăng:\\s*.*\\R?");

    private static final Pattern MULTIPLE_EMPTY_LINES_PATTERN =
            Pattern.compile("(?:\\R[\\t ]*){3,}");

    private static final DateTimeFormatter LOG_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String deviceSerial;
    private final Set<String> collectedHashes = new HashSet<>();

    private MultiChapterCrawlerParaller(String deviceSerial) {
        this.deviceSerial = deviceSerial;
    }
    /** Số lần vuốt tối đa để cố đi hết một chương dài. */
    private static final int MAX_SCROLLS_TO_END_CHAPTER = 250;

    /** Cứ bao nhiêu lần vuốt thì dump XML kiểm tra đã sang chương mới chưa. */
    private static final int CHECK_AFTER_SCROLL_COUNT = 8;
    /** Vuốt xuống để xử lý màn hình loading ở cuối chương. */
    private static final int DOWN_SWIPE_X = 360;
    private static final int DOWN_SWIPE_START_Y = 280;
    private static final int DOWN_SWIPE_END_Y = 1050;
    private static final int DOWN_SWIPE_DURATION_MS = 500;

    /** Khi gặp màn loading cuối chương thì đợi bao lâu. */
    private static final long BOTTOM_LOADING_WAIT_MS = 10_000;

    /** Số lần vuốt xuống khi bị kẹt loading cuối chương. */
    private static final int DOWN_SWIPE_REPEAT_COUNT = 2;

    private boolean scrollUntilChapterChanges(String previousHash)
            throws Exception {

        log("Bắt đầu vuốt cho tới khi sang chương mới...");

        for (int scrollCount = 1;
             scrollCount <= MAX_SCROLLS_TO_END_CHAPTER;
             scrollCount++) {

            swipe(
                    VERTICAL_SWIPE_X,
                    VERTICAL_SWIPE_START_Y,
                    VERTICAL_SWIPE_X,
                    VERTICAL_SWIPE_END_Y,
                    VERTICAL_SWIPE_DURATION_MS
            );

            sleep(VERTICAL_SWIPE_INTERVAL_MS);

            if (scrollCount % CHECK_AFTER_SCROLL_COUNT != 0) {
                continue;
            }

            log("Đã vuốt " + scrollCount + " lần, kiểm tra chương...");

            try {
                Document document = dumpAndReadHierarchy();

                String currentStory = cleanStory(extractStory(document));
                String currentHash = sha256(currentStory);

                if (!currentHash.equals(previousHash)) {
                    log("Đã sang chương mới sau " + scrollCount + " lần vuốt.");
                    return true;
                }

                log("Chưa sang chương mới, thử xử lý kẹt loading cuối chương...");
                handleBottomLoadingStuck();

            } catch (Exception exception) {
                log("Không lấy được nội dung. Có thể đang ở màn loading cuối chương.");
                handleBottomLoadingStuck();
            }
        }

        log("Đã vuốt tối đa " + MAX_SCROLLS_TO_END_CHAPTER
                + " lần nhưng vẫn chưa sang chương mới.");

        return false;
    }
    private void handleBottomLoadingStuck()
            throws IOException, InterruptedException {

        log("Có thể đang kẹt ở màn loading cuối chương.");
        log("Vuốt lên 1 nhịp, đợi 10s, rồi vuốt xuống 2 lần...");

        // Vuốt lên 1 nhịp
        swipe(
                VERTICAL_SWIPE_X,
                VERTICAL_SWIPE_START_Y,
                VERTICAL_SWIPE_X,
                VERTICAL_SWIPE_END_Y,
                VERTICAL_SWIPE_DURATION_MS
        );

        sleep(BOTTOM_LOADING_WAIT_MS);

        // Vuốt xuống 2 lần
        for (int i = 0; i < DOWN_SWIPE_REPEAT_COUNT; i++) {
            swipe(
                    DOWN_SWIPE_X,
                    DOWN_SWIPE_START_Y,
                    DOWN_SWIPE_X,
                    DOWN_SWIPE_END_Y,
                    DOWN_SWIPE_DURATION_MS
            );

            sleep(BOTTOM_LOADING_WAIT_MS);
        }
    }
    public static void main(String[] args) {
        try {
            if (DEVICE_SERIAL == null || DEVICE_SERIAL.isBlank()) {
                runParallelDevices();
                return;
            }

            String serial = resolveDeviceSerial();

            log("==============================================");
            log("BẮT ĐẦU CRAWL TRUYỆN");
            log("Job: " + BOOK_NAME);
            log("Thiết bị: " + serial);
            log("Số chương tối đa: " + MAX_CHAPTERS);
            log("Thư mục kết quả: " + OUTPUT_DIRECTORY.toAbsolutePath());
            log("==============================================");

            MultiChapterCrawlerParaller crawler = new MultiChapterCrawlerParaller(serial);
            crawler.crawl();

        } catch (Exception exception) {
            System.err.println();
            System.err.println("CRAWLER BỊ LỖI: " + exception.getMessage());
            exception.printStackTrace();
        }
    }

    private static void runParallelDevices() throws Exception {
        log("==============================================");
        log("CHẠY SONG SONG " + PARALLEL_DEVICE_SERIALS.size() + " THIẾT BỊ");
        log("Thiết bị: " + PARALLEL_DEVICE_SERIALS);
        log("==============================================");

        String osName = System.getProperty("os.name", "").toLowerCase();
        String javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                osName.contains("win") ? "java.exe" : "java"
        ).toString();

        String classPath = System.getProperty("java.class.path");
        String className = MultiChapterCrawlerParaller.class.getName();

        List<Process> processes = new ArrayList<>();

        for (String serial : PARALLEL_DEVICE_SERIALS) {
            String jobName = serial.replaceAll("[^a-zA-Z0-9._-]+", "_");
            Path logDirectory = Path.of("crawler-work", jobName);
            Files.createDirectories(logDirectory);

            Path logFile = logDirectory.resolve("crawler.log");

            ProcessBuilder processBuilder = new ProcessBuilder(
                    javaExecutable,
                    "-cp",
                    classPath,
                    "-Ddevice=" + serial,
                    "-Dbook=" + jobName,
                    className
            );

            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(
                    ProcessBuilder.Redirect.appendTo(logFile.toFile())
            );

            Process process = processBuilder.start();
            processes.add(process);

            log("Đã chạy crawler cho " + serial);
            log("Log: " + logFile.toAbsolutePath());
            log("Output: " + Path.of("stories", jobName).toAbsolutePath());
        }

        int failedCount = 0;

        for (int i = 0; i < processes.size(); i++) {
            String serial = PARALLEL_DEVICE_SERIALS.get(i);
            int exitCode = processes.get(i).waitFor();

            if (exitCode == 0) {
                log("Crawler " + serial + " đã hoàn thành.");
            } else {
                failedCount++;
                log("Crawler " + serial + " lỗi, exit code = " + exitCode);
            }
        }

        if (failedCount > 0) {
            throw new IllegalStateException(
                    "Có " + failedCount + " crawler bị lỗi. Kiểm tra file crawler.log."
            );
        }

        log("Tất cả crawler đã hoàn thành.");
    }

    private void crawl() throws Exception {
        Files.createDirectories(OUTPUT_DIRECTORY);
        Files.createDirectories(LOCAL_XML_PATH.getParent());

        // Mỗi lần chạy tạo lại file ghép để tránh trùng nội dung.
        Files.writeString(
                MERGED_OUTPUT_FILE,
                "",
                StandardCharsets.UTF_8
        );

        int savedChapterCount = 0;

        for (int sequence = 1; sequence <= MAX_CHAPTERS; sequence++) {
            log("");
            log("========== LẦN ĐỌC " + sequence + " ==========");

            Document document = dumpAndReadHierarchy();
            String rawStory = extractStory(document);
            String cleanedStory = cleanStory(rawStory);

            if (cleanedStory.isBlank()) {
                log("Nội dung chương rỗng. Dừng crawler.");
                break;
            }

            String contentHash = sha256(cleanedStory);

            if (!collectedHashes.add(contentHash)) {
                log("Gặp lại nội dung chương đã lấy. Có thể đang ở chương cuối.");
                break;
            }

            Path chapterFile = saveChapter(cleanedStory, sequence);
            appendToMergedFile(cleanedStory);

            savedChapterCount++;

            log("Đã lưu: " + chapterFile.toAbsolutePath());
            log("Số ký tự: " + cleanedStory.length());

            if (sequence >= MAX_CHAPTERS) {
                log("Đã đạt giới hạn " + MAX_CHAPTERS + " chương.");
                break;
            }

//            boolean moved = moveToNextChapter(document);
//
//            if (!moved) {
//                log("Không thể thực hiện thao tác chuyển chương. Dừng crawler.");
//                break;
//            }
//
//            boolean changed = waitUntilContentChangeswaitUntilContentChanges(contentHash);
//
//            if (!changed) {
//                log("Nội dung không thay đổi sau khi chuyển chương.");
//                log("Có thể app đang ở chương cuối hoặc tọa độ/thao tác chuyển chương chưa đúng.");
//                break;
//            }
            boolean changed;

            if (SWIPE_MODE == SwipeMode.VERTICAL_SCROLL) {
                changed = scrollUntilChapterChanges(contentHash);
            } else {
                boolean moved = moveToNextChapter(document);

                if (!moved) {
                    log("Không thể thực hiện thao tác chuyển chương. Dừng crawler.");
                    break;
                }

                changed = waitUntilContentChanges(contentHash);
            }

            if (!changed) {
                log("Không thể sang chương mới.");
                log("Có thể chương quá dài, app load chậm, hoặc đã tới chương cuối.");
                break;
            }
        }

        log("");
        log("==============================================");
        log("HOÀN THÀNH");
        log("Đã lưu " + savedChapterCount + " chương.");
        log("File ghép: " + MERGED_OUTPUT_FILE.toAbsolutePath());
        log("==============================================");
    }

    /**
     * Chuyển sang chương tiếp theo theo thứ tự:
     * 1. Tìm nút trong XML hiện tại.
     * 2. Bấm giữa màn hình để hiện thanh điều khiển rồi tìm lại.
     * 3. Dùng tọa độ cấu hình.
     * 4. Vuốt (ngang hoặc dọc, tùy SWIPE_MODE) nếu được bật.
     */
    private boolean moveToNextChapter(Document currentDocument) throws Exception {
        if (clickNextButtonFromHierarchy(currentDocument)) {
            return true;
        }

        log("Chưa thấy nút Chương sau. Thử hiện thanh điều khiển...");
        tap(SCREEN_CENTER_X, SCREEN_CENTER_Y);
        sleep(700);

        Document controlsDocument = dumpAndReadHierarchy();

        if (clickNextButtonFromHierarchy(controlsDocument)) {
            return true;
        }

        if (NEXT_BUTTON_X != null && NEXT_BUTTON_Y != null) {
            log("Dùng tọa độ nút Chương sau: "
                    + NEXT_BUTTON_X + ", " + NEXT_BUTTON_Y);

            tap(NEXT_BUTTON_X, NEXT_BUTTON_Y);
            return true;
        }

        if (USE_SWIPE_WHEN_NEXT_BUTTON_NOT_FOUND) {
            if (SWIPE_MODE == SwipeMode.VERTICAL_SCROLL) {
                log("Không thấy nút. Vuốt dọc "
                        + VERTICAL_SWIPE_REPEAT_COUNT
                        + " lần để cuộn hết chương hiện tại...");

                for (int i = 0; i < VERTICAL_SWIPE_REPEAT_COUNT; i++) {
                    swipe(
                            VERTICAL_SWIPE_X,
                            VERTICAL_SWIPE_START_Y,
                            VERTICAL_SWIPE_X,
                            VERTICAL_SWIPE_END_Y,
                            VERTICAL_SWIPE_DURATION_MS
                    );
                    sleep(VERTICAL_SWIPE_INTERVAL_MS);
                }
            } else {
                log("Không thấy nút. Thử vuốt từ phải sang trái...");
                swipe(
                        SWIPE_START_X,
                        SWIPE_START_Y,
                        SWIPE_END_X,
                        SWIPE_END_Y,
                        SWIPE_DURATION_MS
                );
            }
            return true;
        }

        return false;
    }

    private boolean clickNextButtonFromHierarchy(Document document)
            throws IOException, InterruptedException {

        List<Element> candidates = findNextButtonCandidates(document);

        if (candidates.isEmpty()) {
            return false;
        }

        // Ưu tiên node clickable và node nằm thấp hơn trên màn hình.
        candidates.sort(
                Comparator
                        .comparing((Element element) ->
                                !"true".equals(element.getAttribute("clickable")))
                        .thenComparing(
                                element -> -extractTopCoordinate(
                                        element.getAttribute("bounds")
                                )
                        )
        );

        for (Element candidate : candidates) {
            int[] center = extractCenter(candidate.getAttribute("bounds"));

            if (center == null) {
                continue;
            }

            String description = describeNode(candidate);

            log("Tìm thấy nút chương sau: " + description);
            log("Bấm tại: " + center[0] + ", " + center[1]);

            tap(center[0], center[1]);
            return true;
        }

        return false;
    }

    private List<Element> findNextButtonCandidates(Document document) {
        List<Element> candidates = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName("node");

        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);

            String text = normalizeSearchText(
                    element.getAttribute("text")
            );

            String contentDescription = normalizeSearchText(
                    element.getAttribute("content-desc")
            );

            String resourceId = normalizeSearchText(
                    element.getAttribute("resource-id")
            );

            boolean textMatches =
                    text.equals("chuong sau")
                            || text.contains("chuong sau")
                            || text.equals("tiep")
                            || text.equals("tiep theo")
                            || text.contains("next chapter");

            boolean descriptionMatches =
                    contentDescription.contains("chuong sau")
                            || contentDescription.contains("tiep theo")
                            || contentDescription.contains("next chapter");

            boolean idMatches =
                    resourceId.contains("nextchapter")
                            || resourceId.contains("next_chapter")
                            || resourceId.contains("chapter_next")
                            || resourceId.contains("btnnext")
                            || resourceId.contains("btn_next")
                            || resourceId.endsWith("/next")
                            || resourceId.endsWith(":id/next");

            if (textMatches || descriptionMatches || idMatches) {
                candidates.add(element);
            }
        }

        return candidates;
    }

    private boolean waitUntilContentChanges(String previousHash)
            throws Exception {

        long startedAt = System.currentTimeMillis();

        while (System.currentTimeMillis() - startedAt
                < CHAPTER_CHANGE_TIMEOUT_MS) {

            sleep(POLL_INTERVAL_MS);

            try {
                Document document = dumpAndReadHierarchy();
                String currentStory = cleanStory(extractStory(document));
                String currentHash = sha256(currentStory);

                if (!currentHash.equals(previousHash)) {
                    log("Đã nhận được nội dung chương mới.");
                    return true;
                }

                log("Đang chờ chương mới tải...");

                // Nếu app dùng kiểu cuộn dọc, nội dung có thể chưa đổi vì
                // chưa cuộn tới hết chương. Vuốt dọc thêm một lần nữa trong
                // lúc chờ để tiếp tục đẩy tới chương kế.
                if (SWIPE_MODE == SwipeMode.VERTICAL_SCROLL) {
                    swipe(
                            VERTICAL_SWIPE_X,
                            VERTICAL_SWIPE_START_Y,
                            VERTICAL_SWIPE_X,
                            VERTICAL_SWIPE_END_Y,
                            VERTICAL_SWIPE_DURATION_MS
                    );
                }

            } catch (Exception exception) {
                // Khi app đang chuyển màn hình, hierarchy có thể tạm thời chưa đầy đủ.
                log("UI đang thay đổi, tiếp tục chờ...");
            }
        }

        return false;
    }

//    private Document dumpAndReadHierarchy() throws Exception {
//        String dumpOutput = runAdbWithRetry(
//                3,
//                "shell",
//                "uiautomator",
//                "dump",
//                REMOTE_XML_PATH
//        );
//
//        if (!dumpOutput.isBlank()) {
//            log("UI dump: " + oneLine(dumpOutput));
//        }
//
//        String pullOutput = runAdbWithRetry(
//                3,
//                "pull",
//                REMOTE_XML_PATH,
//                LOCAL_XML_PATH.toAbsolutePath().toString()
//        );
//
//        if (!pullOutput.isBlank()) {
//            log("ADB pull: " + oneLine(pullOutput));
//        }
//
//        return parseXml(LOCAL_XML_PATH);
//    }
    private Document dumpAndReadHierarchy() throws Exception {
        Document document = dumpPullAndParseXml();

        if (clickOkPopupIfPresent(document)) {
            sleep(800);
            document = dumpPullAndParseXml();
        }

        return document;
    }

    private Document dumpPullAndParseXml() throws Exception {
        String dumpOutput = runAdbWithRetry(
                3,
                "shell",
                "uiautomator",
                "dump",
                REMOTE_XML_PATH
        );

        if (!dumpOutput.isBlank()) {
            log("UI dump: " + oneLine(dumpOutput));
        }

        String pullOutput = runAdbWithRetry(
                3,
                "pull",
                REMOTE_XML_PATH,
                LOCAL_XML_PATH.toAbsolutePath().toString()
        );

        if (!pullOutput.isBlank()) {
            log("ADB pull: " + oneLine(pullOutput));
        }

        return parseXml(LOCAL_XML_PATH);
    }

    private String extractStory(Document document) {
        NodeList nodes = document.getElementsByTagName("node");

        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            String resourceId = element.getAttribute("resource-id");

            if (CONTENT_RESOURCE_ID.equals(resourceId)
                    || resourceId.endsWith(":id/tvContent")) {

                String text = element.getAttribute("text");

                if (!text.isBlank()) {
                    return text;
                }
            }
        }

        throw new IllegalStateException(
                "Không tìm thấy nội dung truyện trong resource-id tvContent."
        );
    }
    private boolean clickOkPopupIfPresent(Document document)
            throws IOException, InterruptedException {

        NodeList nodes = document.getElementsByTagName("node");

        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);

            String text = element.getAttribute("text");
            String resourceId = element.getAttribute("resource-id");

            boolean isOkButton =
                    "OK".equalsIgnoreCase(text)
                            && "android:id/button1".equals(resourceId);

            if (!isOkButton) {
                continue;
            }

            int[] center = extractCenter(element.getAttribute("bounds"));

            if (center == null) {
                continue;
            }

            log("Phát hiện popup nghỉ mắt. Bấm OK tại: "
                    + center[0] + ", " + center[1]);

            tap(center[0], center[1]);
            return true;
        }

        return false;
    }
    private String cleanStory(String text) {
        String result = text
                .replace('\u00A0', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        // Bỏ dòng người đăng.
        result = POSTER_PATTERN
                .matcher(result)
                .replaceFirst("");

        // Cắt phần ghi chú tác giả hoặc thông báo BQT khỏi nội dung chương.
        List<String> stopMarkers = List.of(
                "\n\nNhất Giới Tàn Hài",
                "\n\n   Nhất Giới Tàn Hài",
                "--------THÔNG BÁO TỪ BQT--------"
        );

        int earliestStopPosition = -1;

        for (String marker : stopMarkers) {
            int position = result.indexOf(marker);

            if (position >= 0
                    && (earliestStopPosition == -1
                    || position < earliestStopPosition)) {

                earliestStopPosition = position;
            }
        }

        if (earliestStopPosition >= 0) {
            result = result.substring(0, earliestStopPosition);
        }

        List<String> cleanedLines = new ArrayList<>();

        for (String line : result.split("\n", -1)) {
            cleanedLines.add(line.stripTrailing());
        }

        result = String.join("\n", cleanedLines).strip();

        // Không để quá hai dòng trống liên tiếp.
        result = MULTIPLE_EMPTY_LINES_PATTERN
                .matcher(result)
                .replaceAll("\n\n");

        return result;
    }

    private Path saveChapter(String content, int sequence)
            throws IOException {

        String title = findFirstNonBlankLine(content);
        Integer chapterNumber = findChapterNumber(title);

        String numberPart = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
                + "_"
                + String.format("%04d", sequence);

        String safeTitle = sanitizeFileName(title);

        if (safeTitle.isBlank()) {
            safeTitle = "chuong_khong_ten";
        }

        if (safeTitle.length() > 120) {
            safeTitle = safeTitle.substring(0, 120);
        }

        Path outputFile = OUTPUT_DIRECTORY.resolve(
                numberPart + "_" + safeTitle + ".txt"
        );

        // Nếu file đã tồn tại, thêm sequence để tránh ghi đè nhầm.
        if (Files.exists(outputFile)) {
            outputFile = OUTPUT_DIRECTORY.resolve(
                    numberPart
                            + "_run_"
                            + String.format("%04d", sequence)
                            + "_"
                            + safeTitle
                            + ".txt"
            );
        }

        Files.writeString(
                outputFile,
                content,
                StandardCharsets.UTF_8
        );

        return outputFile;
    }

    private void appendToMergedFile(String content)
            throws IOException {

        String separator = "\n\n"
                + "=".repeat(80)
                + "\n\n";

        Files.writeString(
                MERGED_OUTPUT_FILE,
                content + separator,
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
        );
    }

    private static String resolveDeviceSerial()
            throws IOException, InterruptedException {

        if (DEVICE_SERIAL != null && !DEVICE_SERIAL.isBlank()) {
            return DEVICE_SERIAL.trim();
        }

        ProcessBuilder processBuilder =
                new ProcessBuilder(ADB_PATH, "devices");

        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IllegalStateException(
                    "Không chạy được adb devices:\n" + output
            );
        }

        List<String> devices = output.lines()
                .map(String::trim)
                .filter(line -> line.endsWith("\tdevice"))
                .map(line -> line.split("\\s+")[0])
                .collect(Collectors.toList());

        if (devices.isEmpty()) {
            throw new IllegalStateException(
                    "Không có thiết bị Android nào đang kết nối."
            );
        }

        if (devices.size() > 1) {
            throw new IllegalStateException(
                    "Có nhiều thiết bị đang kết nối: " + devices
                            + "\nHãy điền DEVICE_SERIAL ở đầu file."
            );
        }

        return devices.get(0);
    }

    private String runAdbWithRetry(
            int maximumAttempts,
            String... arguments
    ) throws IOException, InterruptedException {

        Exception lastException = null;

        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            try {
                return runAdb(arguments);
            } catch (Exception exception) {
                lastException = exception;

                if (attempt < maximumAttempts) {
                    log("Lệnh ADB lỗi, thử lại "
                            + attempt + "/" + maximumAttempts + "...");
                    sleep(700);
                }
            }
        }

        throw new IllegalStateException(
                lastException == null
                        ? "Lệnh ADB thất bại."
                        : lastException.getMessage(),
                lastException
        );
    }

    private String runAdb(String... arguments)
            throws IOException, InterruptedException {

        List<String> command = new ArrayList<>();

        command.add(ADB_PATH);
        command.add("-s");
        command.add(deviceSerial);
        command.addAll(List.of(arguments));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IllegalStateException(
                    "Lệnh ADB thất bại:\n"
                            + String.join(" ", command)
                            + "\n"
                            + output
            );
        }

        return output;
    }

    private Document parseXml(Path xmlFile) throws Exception {
        if (!Files.exists(xmlFile)) {
            throw new IllegalStateException(
                    "Không tìm thấy file XML: "
                            + xmlFile.toAbsolutePath()
            );
        }

        DocumentBuilderFactory factory =
                DocumentBuilderFactory.newInstance();

        // Chặn XXE.
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true
        );
        factory.setFeature(
                "http://xml.org/sax/features/external-general-entities",
                false
        );
        factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities",
                false
        );
        factory.setAttribute(
                XMLConstants.ACCESS_EXTERNAL_DTD,
                ""
        );
        factory.setAttribute(
                XMLConstants.ACCESS_EXTERNAL_SCHEMA,
                ""
        );
        factory.setExpandEntityReferences(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(xmlFile.toFile());
    }

    private void tap(int x, int y)
            throws IOException, InterruptedException {

        runAdb(
                "shell",
                "input",
                "tap",
                String.valueOf(x),
                String.valueOf(y)
        );
    }

    private void swipe(
            int startX,
            int startY,
            int endX,
            int endY,
            int durationMilliseconds
    ) throws IOException, InterruptedException {

        runAdb(
                "shell",
                "input",
                "swipe",
                String.valueOf(startX),
                String.valueOf(startY),
                String.valueOf(endX),
                String.valueOf(endY),
                String.valueOf(durationMilliseconds)
        );
    }

    private static int[] extractCenter(String bounds) {
        Matcher matcher = BOUNDS_PATTERN.matcher(bounds);

        if (!matcher.matches()) {
            return null;
        }

        int left = Integer.parseInt(matcher.group(1));
        int top = Integer.parseInt(matcher.group(2));
        int right = Integer.parseInt(matcher.group(3));
        int bottom = Integer.parseInt(matcher.group(4));

        return new int[]{
                (left + right) / 2,
                (top + bottom) / 2
        };
    }

    private static int extractTopCoordinate(String bounds) {
        Matcher matcher = BOUNDS_PATTERN.matcher(bounds);

        if (!matcher.matches()) {
            return 0;
        }

        return Integer.parseInt(matcher.group(2));
    }

    private static String describeNode(Element element) {
        return "text=\"" + element.getAttribute("text")
                + "\", content-desc=\""
                + element.getAttribute("content-desc")
                + "\", resource-id=\""
                + element.getAttribute("resource-id")
                + "\", bounds=\""
                + element.getAttribute("bounds")
                + "\"";
    }

    private static String findFirstNonBlankLine(String content) {
        for (String line : content.split("\\R")) {
            String value = line.trim();

            if (!value.isBlank()) {
                return value;
            }
        }

        return "chuong_khong_ten";
    }

    private static Integer findChapterNumber(String title) {
        Matcher matcher = CHAPTER_NUMBER_PATTERN.matcher(title);

        if (!matcher.find()) {
            return null;
        }

        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String sanitizeFileName(String value) {
        String normalized = Normalizer.normalize(
                value,
                Normalizer.Form.NFD
        );

        return normalized
                .replaceAll("\\p{M}", "")
                .replaceAll("[\\\\/:*?\"<>|]+", "_")
                .replaceAll("[^a-zA-Z0-9._ -]+", "_")
                .replaceAll("[\\s_]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static String normalizeSearchText(String value) {
        String normalized = Normalizer.normalize(
                value == null ? "" : value,
                Normalizer.Form.NFD
        );

        return normalized
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .trim();
    }

    private static String sha256(String content) throws Exception {
        MessageDigest digest =
                MessageDigest.getInstance("SHA-256");

        byte[] hash = digest.digest(
                content.getBytes(StandardCharsets.UTF_8)
        );

        StringBuilder hex = new StringBuilder(hash.length * 2);

        for (byte value : hash) {
            hex.append(String.format("%02x", value & 0xff));
        }

        return hex.toString();
    }

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Tiến trình bị gián đoạn.",
                    exception
            );
        }
    }

    private static String oneLine(String value) {
        return value
                .replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static void log(String message) {
        System.out.println(
                "[" + LocalDateTime.now().format(LOG_TIME_FORMAT) + "] "
                        + message
        );
    }
}