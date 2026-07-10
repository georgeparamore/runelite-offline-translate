package com.offlinetranslate.model;

public class DownloadProgress
{
	private final String currentFile;
	private final int fileIndex;
	private final int fileCount;
	private final long bytesDownloadedForFile;
	private final long totalBytesForFile;

	public DownloadProgress(String currentFile, int fileIndex, int fileCount, long bytesDownloadedForFile, long totalBytesForFile)
	{
		this.currentFile = currentFile;
		this.fileIndex = fileIndex;
		this.fileCount = fileCount;
		this.bytesDownloadedForFile = bytesDownloadedForFile;
		this.totalBytesForFile = totalBytesForFile;
	}

	public String getCurrentFile()
	{
		return currentFile;
	}

	/** Overall progress across all files in the pack, 0.0-1.0. */
	public double overallFraction()
	{
		double perFile = 1.0 / fileCount;
		double currentFileFraction = totalBytesForFile > 0 ? (double) bytesDownloadedForFile / totalBytesForFile : 0.0;
		return (fileIndex * perFile) + (currentFileFraction * perFile);
	}

	public int getFileIndex()
	{
		return fileIndex;
	}

	public int getFileCount()
	{
		return fileCount;
	}
}
