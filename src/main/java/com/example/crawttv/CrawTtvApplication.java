package com.example.crawttv;

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

	private static final String BASE_URL =
			"https://tangthuvien.org/huyen-tran-dao-do/";

	private static final Path OUTPUT_DIRECTORY = Path.of("output");

	private static final long REQUEST_DELAY_MILLIS = 9_500L;

	public static void main(String[] args) {
		int fromChapter = getEnvInt("FROM_CHAPTER", 4);
		int toChapter = getEnvInt("TO_CHAPTER", 753);

		if (fromChapter <= 0) {
			throw new IllegalArgumentException(
					"FROM_CHAPTER phải lớn hơn 0"
			);
		}

		if (toChapter < fromChapter) {
			throw new IllegalArgumentException(
					"TO_CHAPTER phải lớn hơn hoặc bằng FROM_CHAPTER"
			);
		}

		System.out.printf(
				"Bắt đầu crawl từ chương %d đến chương %d%n",
				fromChapter,
				toChapter
		);

		int successCount = 0;
		int skippedCount = 0;
		int failedCount = 0;

		for (int chapter = fromChapter; chapter <= toChapter; chapter++) {
			try {
				if (chapterExists(chapter)) {
					System.out.printf(
							"Bỏ qua chương %d vì file đã tồn tại%n",
							chapter
					);

					skippedCount++;
					continue;
				}

				ChapterData data = crawlChapter(chapter);
				saveChapter(data);

				successCount++;

				System.out.printf(
						"Đã lấy chương %d: %s%n",
						data.chapterNumber(),
						data.title()
				);

			} catch (HttpStatusException exception) {
				failedCount++;

				System.err.printf(
						"Chương %d trả về HTTP %d: %s%n",
						chapter,
						exception.getStatusCode(),
						exception.getUrl()
				);

			} catch (Exception exception) {
				failedCount++;

				System.err.printf(
						"Không thể lấy chương %d: %s%n",
						chapter,
						exception.getMessage()
				);

			} finally {
				// Không cần chờ sau chương cuối cùng
				if (chapter < toChapter) {
					sleepBeforeNextRequest();
				}
			}
		}

		System.out.println();
		System.out.println("Hoàn thành crawler.");
		System.out.println("Thành công: " + successCount);
		System.out.println("Đã tồn tại: " + skippedCount);
		System.out.println("Thất bại: " + failedCount);
		System.out.println("Chương cuối được yêu cầu: " + toChapter);
	}

	private static ChapterData crawlChapter(int chapterNumber)
			throws IOException {

		String url = BASE_URL + chapterNumber;

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

		Elements paragraphs = contentElement.select("p");

		StringBuilder content = new StringBuilder();

		for (Element paragraph : paragraphs) {
			String text = paragraph.text().trim();

			if (!text.isBlank()) {
				content.append(text).append("\n\n");
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

	private static void saveChapter(ChapterData data)
			throws IOException {

		Files.createDirectories(OUTPUT_DIRECTORY);

		Path outputFile = getChapterFile(data);

		String fileContent =
				data.title() + "\n\n" +
						data.content() + "\n\n" +
						"Nguồn: " + data.url() + "\n";

		Files.writeString(
				outputFile,
				fileContent,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING
		);
	}

	private static boolean chapterExists(int chapterNumber)
			throws IOException {

		if (!Files.exists(OUTPUT_DIRECTORY)) {
			return false;
		}

		String chapterPrefix = String.format(
				"%04d-",
				chapterNumber
		);

		try (var files = Files.list(OUTPUT_DIRECTORY)) {
			return files
					.filter(Files::isRegularFile)
					.anyMatch(path ->
							path.getFileName()
									.toString()
									.startsWith(chapterPrefix)
					);
		}
	}

	private static Path getChapterFile(ChapterData data) {
		String filename = String.format(
				"%04d-%s.txt",
				data.chapterNumber(),
				sanitizeFilename(data.title())
		);

		return OUTPUT_DIRECTORY.resolve(filename);
	}

	private static int getEnvInt(
			String variableName,
			int defaultValue
	) {
		String value = System.getenv(variableName);

		if (value == null || value.isBlank()) {
			return defaultValue;
		}

		try {
			return Integer.parseInt(value.trim());

		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException(
					variableName +
							" không phải là số hợp lệ: " +
							value
			);
		}
	}

	private static void sleepBeforeNextRequest() {
		try {
			Thread.sleep(REQUEST_DELAY_MILLIS);

		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();

			throw new IllegalStateException(
					"Crawler bị dừng khi đang chờ",
					exception
			);
		}
	}

	private static String sanitizeFilename(String input) {
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