package com.example.crawttv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@SpringBootApplication
public class CrawTtvApplication {

	private static final String WEBSITE_URL =
			"https://tangthuvien.org/";

	private static final Path CONFIG_FILE =
			Path.of("config", "crawler-config.json");

	private static final Path OUTPUT_ROOT =
			Path.of("output");

	private static final ObjectMapper OBJECT_MAPPER =
			new ObjectMapper()
					.enable(SerializationFeature.INDENT_OUTPUT);

	public static void main(String[] args) {
		try {
			CrawlerConfig config = readConfig();
			validateConfig(config);
			runCrawler(config);
		} catch (Exception exception) {
			System.err.println(
					"Crawler kết thúc do lỗi: " +
							exception.getMessage()
			);

			exception.printStackTrace();
			System.exit(1);
		}
	}

	private static void runCrawler(CrawlerConfig config)
			throws IOException {

		if (!config.isEnabled()) {
			System.out.println(
					"Truyện đang bị tắt trong cấu hình."
			);
			return;
		}

		if (config.getNextChapter() > config.getToChapter()) {
			System.out.printf(
					"Đã crawl xong truyện \"%s\" đến chương %d.%n",
					config.getBookName(),
					config.getToChapter()
			);
			return;
		}

		int fromChapter = Math.max(
				config.getNextChapter(),
				config.getFromChapter()
		);

		int calculatedToChapter =
				fromChapter + config.getBatchSize() - 1;

		int batchToChapter = Math.min(
				calculatedToChapter,
				config.getToChapter()
		);

		System.out.printf(
				"Truyện: %s%n",
				config.getBookName()
		);

		System.out.printf(
				"Slug: %s%n",
				config.getSlug()
		);

		System.out.printf(
				"Batch hiện tại: chương %d đến chương %d%n",
				fromChapter,
				batchToChapter
		);

		int successCount = 0;
		int skippedCount = 0;
		int failedCount = 0;

		/*
		 * Chỉ cập nhật nextChapter đến chương đã xử lý liên tục.
		 * Nếu một chương lỗi, crawler dừng batch để lần sau thử lại
		 * đúng chương đó.
		 */
		int nextChapter = fromChapter;

		for (
				int chapter = fromChapter;
				chapter <= batchToChapter;
				chapter++
		) {
			try {
				if (chapterExists(config, chapter)) {
					System.out.printf(
							"Bỏ qua chương %d vì file đã tồn tại.%n",
							chapter
					);

					skippedCount++;
					nextChapter = chapter + 1;
					continue;
				}

				ChapterData data = crawlChapter(
						config,
						chapter
				);

				saveChapter(config, data);

				successCount++;
				nextChapter = chapter + 1;

				/*
				 * Ghi trạng thái sau từng chương.
				 * Nếu Action bị dừng giữa chừng thì vẫn nhớ được
				 * chương cuối đã tải.
				 */
				config.setNextChapter(nextChapter);
				writeConfig(config);

				System.out.printf(
						"Đã lấy chương %d: %s%n",
						data.chapterNumber(),
						data.title()
				);

			} catch (HttpStatusException exception) {
				failedCount++;

				System.err.printf(
						"Chương %d trả về HTTP %d.%n",
						chapter,
						exception.getStatusCode()
				);

				/*
				 * Dừng để lần chạy sau thử lại chương đang lỗi.
				 */
				break;

			} catch (Exception exception) {
				failedCount++;

				System.err.printf(
						"Không thể lấy chương %d: %s%n",
						chapter,
						exception.getMessage()
				);

				break;
			}

			if (chapter < batchToChapter) {
				sleep(config.getRequestDelayMillis());
			}
		}

		config.setNextChapter(nextChapter);
		writeConfig(config);

		System.out.println();
		System.out.println("Kết quả batch:");
		System.out.println("Thành công: " + successCount);
		System.out.println("Đã tồn tại: " + skippedCount);
		System.out.println("Thất bại: " + failedCount);
		System.out.println(
				"Chương tiếp theo: " +
						config.getNextChapter()
		);

		if (config.getNextChapter() > config.getToChapter()) {
			System.out.printf(
					"Đã hoàn thành toàn bộ truyện đến chương %d.%n",
					config.getToChapter()
			);
		}
	}

	private static ChapterData crawlChapter(
			CrawlerConfig config,
			int chapterNumber
	) throws IOException {

		String url = buildChapterUrl(
				config,
				chapterNumber
		);

		Document document = Jsoup.connect(url)
				.userAgent(
						"Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
								"AppleWebKit/537.36 Chrome/149 Safari/537.36"
				)
				.header(
						"Accept-Language",
						"vi-VN,vi;q=0.9,en;q=0.8"
				)
				.timeout(20_000)
				.followRedirects(true)
				.get();

		Element titleElement =
				document.selectFirst("h1.text-xl");

		Element contentElement =
				document.selectFirst(
						"div.py-2.flex.flex-col.gap-4"
				);

		if (titleElement == null) {
			throw new IllegalStateException(
					"Không tìm thấy tiêu đề chương"
			);
		}

		if (contentElement == null) {
			throw new IllegalStateException(
					"Không tìm thấy nội dung chương"
			);
		}

		String title = titleElement.text()
				.replaceFirst("^#\\d+\\s*", "")
				.trim();

		Elements paragraphs =
				contentElement.select("p");

		StringBuilder content =
				new StringBuilder();

		for (Element paragraph : paragraphs) {
			String text =
					paragraph.text().trim();

			if (!text.isBlank()) {
				content
						.append(text)
						.append("\n\n");
			}
		}

		if (content.isEmpty()) {
			throw new IllegalStateException(
					"Nội dung chương đang trống"
			);
		}

		return new ChapterData(
				chapterNumber,
				title,
				content.toString().trim(),
				url
		);
	}

	private static void saveChapter(
			CrawlerConfig config,
			ChapterData data
	) throws IOException {

		Path bookDirectory =
				getBookOutputDirectory(config);

		Files.createDirectories(bookDirectory);

		String filename = String.format(
				"%04d-%s.txt",
				data.chapterNumber(),
				sanitizeFilename(data.title())
		);

		String fileContent =
				data.title() + "\n\n" +
						data.content() + "\n\n" +
						"Nguồn: " + data.url() + "\n";

		Files.writeString(
				bookDirectory.resolve(filename),
				fileContent,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING
		);
	}

	private static boolean chapterExists(
			CrawlerConfig config,
			int chapterNumber
	) throws IOException {

		Path bookDirectory =
				getBookOutputDirectory(config);

		if (!Files.exists(bookDirectory)) {
			return false;
		}

		String prefix = String.format(
				"%04d-",
				chapterNumber
		);

		try (var files = Files.list(bookDirectory)) {
			return files
					.filter(Files::isRegularFile)
					.anyMatch(path ->
							path.getFileName()
									.toString()
									.startsWith(prefix)
					);
		}
	}

	private static Path getBookOutputDirectory(
			CrawlerConfig config
	) {
		return OUTPUT_ROOT.resolve(config.getSlug());
	}

	private static String buildChapterUrl(
			CrawlerConfig config,
			int chapterNumber
	) {
		return WEBSITE_URL +
				config.getSlug() +
				"/" +
				chapterNumber;
	}

	private static CrawlerConfig readConfig()
			throws IOException {

		if (!Files.exists(CONFIG_FILE)) {
			throw new IllegalStateException(
					"Không tìm thấy file cấu hình: " +
							CONFIG_FILE
			);
		}

		return OBJECT_MAPPER.readValue(
				CONFIG_FILE.toFile(),
				CrawlerConfig.class
		);
	}

	private static void writeConfig(
			CrawlerConfig config
	) throws IOException {

		Files.createDirectories(
				CONFIG_FILE.getParent()
		);

		OBJECT_MAPPER.writeValue(
				CONFIG_FILE.toFile(),
				config
		);
	}

	private static void validateConfig(
			CrawlerConfig config
	) {
		if (
				config.getBookName() == null ||
						config.getBookName().isBlank()
		) {
			throw new IllegalArgumentException(
					"bookName không được để trống"
			);
		}

		if (
				config.getSlug() == null ||
						config.getSlug().isBlank()
		) {
			throw new IllegalArgumentException(
					"slug không được để trống"
			);
		}

		if (config.getFromChapter() <= 0) {
			throw new IllegalArgumentException(
					"fromChapter phải lớn hơn 0"
			);
		}

		if (
				config.getToChapter() <
						config.getFromChapter()
		) {
			throw new IllegalArgumentException(
					"toChapter phải lớn hơn hoặc bằng fromChapter"
			);
		}

		if (config.getNextChapter() <= 0) {
			throw new IllegalArgumentException(
					"nextChapter phải lớn hơn 0"
			);
		}

		if (config.getBatchSize() <= 0) {
			throw new IllegalArgumentException(
					"batchSize phải lớn hơn 0"
			);
		}

		if (config.getRequestDelayMillis() < 1000) {
			throw new IllegalArgumentException(
					"requestDelayMillis nên từ 1000 trở lên"
			);
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();

			throw new IllegalStateException(
					"Crawler bị dừng khi đang chờ",
					exception
			);
		}
	}

	private static String sanitizeFilename(
			String input
	) {
		return input
				.replaceAll("[\\\\/:*?\"<>|]", "")
				.replaceAll("\\s+", " ")
				.trim();
	}

	record ChapterData(
			int chapterNumber,
			String title,
			String content,
			String url
	) {
	}
}