package com.example.crawttv;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
@SpringBootApplication
public class CrawTtvApplication {

	private static final String BASE_URL =
			"https://tangthuvien.org/huyen-tran-dao-do/";

	public static void main(String[] args) {
		int fromChapter = 4;
		int toChapter = 753;

		for (int chapter = fromChapter; chapter <= toChapter; chapter++) {
			try {
				ChapterData data = crawlChapter(chapter);

				System.out.println("Đã lấy: " + data.title());

				saveChapter(data);
				Thread.sleep(9500);

			} catch (Exception e) {
				System.err.println(
						"Không thể lấy chương " + chapter + ": " + e.getMessage()
				);
			}
		}
	}

	private static ChapterData crawlChapter(int chapterNumber)
			throws IOException {

		String url = BASE_URL + chapterNumber;

		Document document = Jsoup.connect(url)
				.userAgent(
						"Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
								"AppleWebKit/537.36 Chrome/149 Safari/537.36"
				)
				.header("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8")
				.timeout(20_000)
				.followRedirects(true)
				.get();

		Element titleElement = document.selectFirst("h1.text-xl");

		Element contentElement =
				document.selectFirst("div.py-2.flex.flex-col.gap-4");

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

		Path outputDirectory = Path.of("output");
		Files.createDirectories(outputDirectory);

		String filename = String.format(
				"%04d-%s.txt",
				data.chapterNumber(),
				sanitizeFilename(data.title())
		);

		String fileContent =
				data.title() + "\n\n" +
						data.content() + "\n\n" +
						"Nguồn: " + data.url();

		Files.writeString(
				outputDirectory.resolve(filename),
				fileContent,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING
		);
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
