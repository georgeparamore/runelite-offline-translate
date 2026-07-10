package com.offlinetranslate.model;

import com.offlinetranslate.Language;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Downloads and caches OPUS-MT ONNX language packs from Hugging Face (Xenova's ONNX
 * conversions of the Helsinki-NLP OPUS-MT models) into RuneLite's data directory, so that
 * translation can run entirely offline after the one-time download. Nothing here is sent to a
 * translation API at inference time - the download is fetching model weights, not translating
 * player text.
 */
@Slf4j
@Singleton
public class ModelManager
{
	private static final String HF_BASE = "https://huggingface.co/";
	private static final String[] PACK_FILES = {
		"config.json",
		"generation_config.json",
		"tokenizer.json",
		"tokenizer_config.json",
		"source.spm",
		"target.spm",
		"onnx/encoder_model_quantized.onnx",
		"onnx/decoder_model_merged_quantized.onnx"
	};
	private static final String COMPLETE_MARKER = ".complete";

	private final File baseDir = new File(RuneLite.RUNELITE_DIR, "offline-translate/models");
	private final HttpClient httpClient = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();
	private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(2);
	private final Map<String, PackStatus> statusOverrides = new ConcurrentHashMap<>();

	@Inject
	public ModelManager()
	{
	}

	public File getPackDir(Language language, PackDirection direction)
	{
		String repo = direction == PackDirection.TO_ENGLISH ? language.toEnglishRepo() : language.fromEnglishRepo();
		return new File(baseDir, repo.replace('/', '_'));
	}

	public PackStatus getStatus(Language language, PackDirection direction)
	{
		if (direction == PackDirection.FROM_ENGLISH && !language.supportsTranslationFromEnglish())
		{
			return PackStatus.ERROR;
		}

		String key = key(language, direction);
		PackStatus override = statusOverrides.get(key);
		if (override == PackStatus.DOWNLOADING || override == PackStatus.ERROR)
		{
			return override;
		}

		File markerFile = new File(getPackDir(language, direction), COMPLETE_MARKER);
		return markerFile.exists() ? PackStatus.READY : PackStatus.NOT_DOWNLOADED;
	}

	/**
	 * Downloads all files for a language pack in the given direction, reporting progress on
	 * the caller-supplied listener. The listener and returned future both complete on the
	 * download executor, not the EDT - callers touching Swing state must hop back with
	 * SwingUtilities.invokeLater.
	 */
	public CompletableFuture<Void> download(Language language, PackDirection direction, Consumer<DownloadProgress> onProgress)
	{
		if (direction == PackDirection.FROM_ENGLISH && !language.supportsTranslationFromEnglish())
		{
			CompletableFuture<Void> failed = new CompletableFuture<>();
			failed.completeExceptionally(new IllegalStateException(
				"No English->" + language.getDisplayName() + " model is available upstream"));
			return failed;
		}

		String key = key(language, direction);
		statusOverrides.put(key, PackStatus.DOWNLOADING);

		String repo = direction == PackDirection.TO_ENGLISH ? language.toEnglishRepo() : language.fromEnglishRepo();
		File packDir = getPackDir(language, direction);

		return CompletableFuture.runAsync(() -> {
			try
			{
				Files.createDirectories(packDir.toPath());
				for (int i = 0; i < PACK_FILES.length; i++)
				{
					String file = PACK_FILES[i];
					downloadFile(repo, file, packDir, i, onProgress);
				}
				Files.write(new File(packDir, COMPLETE_MARKER).toPath(), new byte[0]);
				statusOverrides.remove(key);
				log.info("Downloaded language pack {} ({})", repo, direction);
			}
			catch (Exception e)
			{
				statusOverrides.put(key, PackStatus.ERROR);
				log.warn("Failed to download language pack {} ({})", repo, direction, e);
				throw new RuntimeException("Failed to download " + repo, e);
			}
		}, downloadExecutor);
	}

	private void downloadFile(String repo, String file, File packDir, int fileIndex, Consumer<DownloadProgress> onProgress) throws IOException, InterruptedException
	{
		URI uri = URI.create(HF_BASE + repo + "/resolve/main/" + file);
		File target = new File(packDir, file);
		Files.createDirectories(target.getParentFile().toPath());
		Path tmp = target.toPath().resolveSibling(target.getName() + ".part");

		HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
		HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200)
		{
			throw new IOException("HTTP " + response.statusCode() + " downloading " + uri);
		}

		long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
		long downloaded = 0;

		try (java.io.InputStream in = response.body();
			 java.io.OutputStream out = Files.newOutputStream(tmp))
		{
			byte[] buffer = new byte[64 * 1024];
			int read;
			while ((read = in.read(buffer)) != -1)
			{
				out.write(buffer, 0, read);
				downloaded += read;
				if (onProgress != null)
				{
					onProgress.accept(new DownloadProgress(file, fileIndex, PACK_FILES.length, downloaded, total));
				}
			}
		}

		Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}

	public void deletePack(Language language, PackDirection direction)
	{
		File dir = getPackDir(language, direction);
		if (!dir.exists())
		{
			return;
		}
		File[] files = dir.listFiles();
		if (files != null)
		{
			for (File f : files)
			{
				f.delete();
			}
		}
		dir.delete();
		statusOverrides.remove(key(language, direction));
	}

	public void shutdown()
	{
		downloadExecutor.shutdownNow();
	}

	private String key(Language language, PackDirection direction)
	{
		return language.getCode() + ":" + direction;
	}
}
