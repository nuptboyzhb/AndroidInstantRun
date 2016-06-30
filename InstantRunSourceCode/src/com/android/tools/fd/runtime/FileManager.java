package com.android.tools.fd.runtime;

import android.content.Context;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FileManager {
	private static final boolean USE_EXTRACTED_RESOURCES = false;
	private static final String RESOURCE_FILE_NAME = "resources.ap_";
	private static final String RESOURCE_FOLDER_NAME = "resources";
	private static final String FILE_NAME_ACTIVE = "active";
	private static final String FOLDER_NAME_LEFT = "left";
	private static final String FOLDER_NAME_RIGHT = "right";
	private static final String RELOAD_DEX_PREFIX = "reload";
	public static final String CLASSES_DEX_SUFFIX = ".dex";
	private static boolean sHavePurgedTempDexFolder;

	private static File getDataFolder() {
		return new File(Paths.getDataDirectory(AppInfo.applicationId));
	}

	private static File getResourceFile(File base) {
		return new File(base, "resources.ap_");
	}

	private static File getDexFileFolder(File base, boolean createIfNecessary) {
		File file = new File(base, "dex");
		if ((createIfNecessary) && (!file.isDirectory())) {
			boolean created = file.mkdirs();
			if (!created) {
				Log.e("InstantRun", "Failed to create directory " + file);
				return null;
			}
		}
		return file;
	}

	private static File getTempDexFileFolder(File base) {
		return new File(base, "dex-temp");
	}

	public static File getNativeLibraryFolder() {
		return new File(Paths.getMainApkDataDirectory(AppInfo.applicationId),
				"lib");
	}

	public static File getReadFolder() {
		String name = leftIsActive() ? "left" : "right";
		return new File(getDataFolder(), name);
	}

	public static void swapFolders() {
		setLeftActive(!leftIsActive());
	}

	public static File getWriteFolder(boolean wipe) {
		String name = leftIsActive() ? "right" : "left";
		File folder = new File(getDataFolder(), name);
		if ((wipe) && (folder.exists())) {
			delete(folder);
			boolean mkdirs = folder.mkdirs();
			if (!mkdirs) {
				Log.e("InstantRun", "Failed to create folder " + folder);
			}
		}
		return folder;
	}

	private static void delete(File file) {
		if (file.isDirectory()) {
			File[] files = file.listFiles();
			if (files != null) {
				for (File child : files) {
					delete(child);
				}
			}
		}
		boolean deleted = file.delete();
		if (!deleted) {
			Log.e("InstantRun", "Failed to delete file " + file);
		}
	}

	private static boolean leftIsActive() {
		File folder = getDataFolder();
		File pointer = new File(folder, "active");
		if (!pointer.exists()) {
			return true;
		}
		try {
			BufferedReader reader = new BufferedReader(new FileReader(pointer));
			try {
				String line = reader.readLine();
				return "left".equals(line);
			} finally {
				reader.close();
			}
		} catch (IOException ignore) {
			return false;
		}
	}

	private static void setLeftActive(boolean active) {
		File folder = getDataFolder();
		File pointer = new File(folder, "active");
		if (pointer.exists()) {
			boolean deleted = pointer.delete();
			if (!deleted) {
				Log.e("InstantRun", "Failed to delete file " + pointer);
			}
		} else if (!folder.exists()) {
			boolean create = folder.mkdirs();
			if (!create) {
				Log.e("InstantRun", "Failed to create directory " + folder);
			}
			return;
		}
		try {
			Writer writer = new BufferedWriter(new OutputStreamWriter(
					new FileOutputStream(pointer), "UTF-8"));
			try {
				writer.write(active ? "left" : "right");
			} finally {
				writer.close();
			}
		} catch (IOException ignore) {
		}
	}

	public static void checkInbox() {
		File inbox = new File(Paths.getInboxDirectory(AppInfo.applicationId));
		if (inbox.isDirectory()) {
			File resources = new File(inbox, "resources.ap_");
			if (resources.isFile()) {
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun", "Processing resource file from inbox ("
							+ resources + ")");
				}
				byte[] bytes = readRawBytes(resources);
				if (bytes != null) {
					startUpdate();
					writeAaptResources("resources.ap_", bytes);
					finishUpdate(true);
					boolean deleted = resources.delete();
					if ((!deleted) && (Log.isLoggable("InstantRun", 6))) {
						Log.e("InstantRun",
								"Couldn't remove inbox resource file: "
										+ resources);
					}
				}
			}
		}
	}

	public static File getExternalResourceFile() {
		File file = getResourceFile(getReadFolder());
		if (!file.exists()) {
			if (Log.isLoggable("InstantRun", 2)) {
				Log.v("InstantRun",
						"Cannot find external resources, not patching them in");
			}
			return null;
		}
		return file;
	}

	public static List<String> getDexList(Context context, long apkModified) {
		File dataFolder = getDataFolder();

		long newestHotswapPatch = getMostRecentTempDexTime(dataFolder);

		File dexFolder = getDexFileFolder(dataFolder, false);

		boolean extractedSlices = false;
		File[] dexFiles;
		if ((dexFolder == null) || (!dexFolder.isDirectory())) {
			if (Log.isLoggable("InstantRun", 2)) {
				Log.v("InstantRun",
						"No local dex slice folder: First run since installation.");
			}
			dexFolder = getDexFileFolder(dataFolder, true);
			if (dexFolder == null) {
				Log.wtf("InstantRun", "Couldn't create dex code folder");
				return Collections.emptyList();
			}
			dexFiles = extractSlices(dexFolder, null, -1L);
			extractedSlices = dexFiles.length > 0;
		} else {
			dexFiles = dexFolder.listFiles();
		}
		if ((dexFiles == null) || (dexFiles.length == 0)) {
			if (Log.isLoggable("InstantRun", 2)) {
				Log.v("InstantRun",
						"Cannot find dex classes, not patching them in");
			}
			return Collections.emptyList();
		}
		long newestColdswapPatch = apkModified;
		if ((!extractedSlices) && (dexFiles.length > 0)) {
			long oldestColdSwapPatch = apkModified;
			for (File dex : dexFiles) {
				long dexModified = dex.lastModified();
				oldestColdSwapPatch = Math
						.min(dexModified, oldestColdSwapPatch);
				newestColdswapPatch = Math
						.max(dexModified, newestColdswapPatch);
			}
			if (oldestColdSwapPatch < apkModified) {
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun",
							"One or more slices were older than APK: extracting newer slices");
				}
				dexFiles = extractSlices(dexFolder, dexFiles, apkModified);
			}
		} else if (newestHotswapPatch > 0L) {
			purgeTempDexFiles(dataFolder);
		}
		if (newestHotswapPatch > newestColdswapPatch) {
			String message = "Your app does not have the latest code changes because it was restarted manually. Please run from IDE instead.";
			if (Log.isLoggable("InstantRun", 2)) {
				Log.v("InstantRun", message);
			}
			Restarter.showToastWhenPossible(context, message);
		}
		List<String> list = new ArrayList(dexFiles.length);
		for (File dex : dexFiles) {
			if (dex.getName().endsWith(".dex")) {
				list.add(dex.getPath());
			}
		}
		Collections.sort(list, Collections.reverseOrder());

		return list;
	}

	/* Error */
	private static File[] extractSlices(File dexFolder, File[] dexFolderFiles,
			long apkModified) {
		return null;
	}

	public static File getTempDexFile() {
		File dataFolder = getDataFolder();
		File dexFolder = getTempDexFileFolder(dataFolder);
		if (!dexFolder.exists()) {
			boolean created = dexFolder.mkdirs();
			if (!created) {
				Log.e("InstantRun", "Failed to create directory " + dexFolder);
				return null;
			}
		} else if (!sHavePurgedTempDexFolder) {
			purgeTempDexFiles(dataFolder);
		}
		File[] files = dexFolder.listFiles();
		int max = -1;
		if (files != null) {
			for (File file : files) {
				String name = file.getName();
				if ((name.startsWith("reload")) && (name.endsWith(".dex"))) {
					String middle = name.substring("reload".length(),
							name.length() - ".dex".length());
					try {
						int version = Integer.decode(middle).intValue();
						if (version > max) {
							max = version;
						}
					} catch (NumberFormatException ignore) {
					}
				}
			}
		}
		String fileName = String.format("%s0x%04x%s", new Object[] { "reload",
				Integer.valueOf(max + 1), ".dex" });

		File file = new File(dexFolder, fileName);
		if (Log.isLoggable("InstantRun", 2)) {
			Log.v("InstantRun", "Writing new dex file: " + file);
		}
		return file;
	}

	/* Error */
	public static boolean writeRawBytes(File destination, byte[] bytes) {
		//TODO 
		return false;
	}

	public static boolean extractZip(File destination, byte[] zipBytes) {
		InputStream inputStream = new ByteArrayInputStream(zipBytes);
		return extractZip(destination, inputStream);
	}

	public static boolean extractZip(File destDir, InputStream inputStream) {
		ZipInputStream zipInputStream = new ZipInputStream(inputStream);
		try {
			byte[] buffer = new byte['?'];
			for (ZipEntry entry = zipInputStream.getNextEntry(); entry != null; entry = zipInputStream
					.getNextEntry()) {
				String name = entry.getName();
				if (!name.startsWith("META-INF")) {
					if (!entry.isDirectory()) {
						File dest = new File(destDir, name);
						File parent = dest.getParentFile();
						if ((parent != null) && (!parent.exists())) {
							boolean created = parent.mkdirs();
							if (!created) {
								Log.e("InstantRun",
										"Failed to create directory " + dest);
								return false;
							}
						}
						OutputStream src = new BufferedOutputStream(
								new FileOutputStream(dest));
						try {
							int bytesRead;
							while ((bytesRead = zipInputStream.read(buffer)) != -1) {
								src.write(buffer, 0, bytesRead);
							}
						} finally {
							src.close();
						}
					}
				}
			}
			return true;
		} catch (IOException ioe) {
			ZipEntry entry;
			Log.e("InstantRun",
					"Failed to extract zip contents into directory " + destDir,
					ioe);
			return false;
		} finally {
			try {
				zipInputStream.close();
			} catch (IOException ignore) {
			}
		}
	}

	public static void startUpdate() {
		getWriteFolder(true);
	}

	public static void finishUpdate(boolean wroteResources) {
		if (wroteResources) {
			swapFolders();
		}
	}

	public static File writeDexShard(byte[] bytes, String name) {
		File dexFolder = getDexFileFolder(getDataFolder(), true);
		if (dexFolder == null) {
			return null;
		}
		File file = new File(dexFolder, name);
		writeRawBytes(file, bytes);
		return file;
	}

	public static void writeAaptResources(String relativePath, byte[] bytes) {
		File resourceFile = getResourceFile(getWriteFolder(false));
		File file = resourceFile;

		File folder = file.getParentFile();
		if (!folder.isDirectory()) {
			boolean created = folder.mkdirs();
			if (!created) {
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun",
							"Cannot create local resource file directory "
									+ folder);
				}
				return;
			}
		}
		if (relativePath.equals("resources.ap_")) {
			writeRawBytes(file, bytes);
		} else {
			writeRawBytes(file, bytes);
		}
	}

	public static String writeTempDexFile(byte[] bytes) {
		File file = getTempDexFile();
		if (file != null) {
			writeRawBytes(file, bytes);
			return file.getPath();
		}
		Log.e("InstantRun", "No file to write temp dex content to");

		return null;
	}

	public static long getMostRecentTempDexTime(File dataFolder) {
		File dexFolder = getTempDexFileFolder(dataFolder);
		if (!dexFolder.isDirectory()) {
			return 0L;
		}
		File[] files = dexFolder.listFiles();
		if (files == null) {
			return 0L;
		}
		long newest = 0L;
		for (File file : files) {
			if (file.getPath().endsWith(".dex")) {
				newest = Math.max(newest, file.lastModified());
			}
		}
		return newest;
	}

	public static void purgeTempDexFiles(File dataFolder) {
		sHavePurgedTempDexFolder = true;

		File dexFolder = getTempDexFileFolder(dataFolder);
		if (!dexFolder.isDirectory()) {
			return;
		}
		File[] files = dexFolder.listFiles();
		if (files == null) {
			return;
		}
		for (File file : files) {
			if (file.getPath().endsWith(".dex")) {
				boolean deleted = file.delete();
				if (!deleted) {
					Log.e("InstantRun", "Could not delete temp dex file "
							+ file);
				}
			}
		}
	}

	public static long getFileSize(String path) {
		if (path.equals("resources.ap_")) {
			File file = getExternalResourceFile();
			if (file != null) {
				return file.length();
			}
		}
		return -1L;
	}

	public static byte[] getCheckSum(String path) {
		if (path.equals("resources.ap_")) {
			File file = getExternalResourceFile();
			if (file != null) {
				return getCheckSum(file);
			}
		}
		return null;
	}

	public static byte[] getCheckSum(File file) {
		try {
			MessageDigest digest = MessageDigest.getInstance("MD5");
			byte[] buffer = new byte['?'];
			BufferedInputStream input = new BufferedInputStream(
					new FileInputStream(file));
			try {
				int read;
				for (;;) {
					read = input.read(buffer);
					if (read == -1) {
						break;
					}
					digest.update(buffer, 0, read);
				}
				return digest.digest();
			} finally {
				input.close();
			}
		} catch (NoSuchAlgorithmException e) {
			if (Log.isLoggable("InstantRun", 6)) {
				Log.e("InstantRun", "Couldn't look up message digest", e);
			}
		} catch (IOException ioe) {
			if (Log.isLoggable("InstantRun", 6)) {
				Log.e("InstantRun", "Failed to read file " + file, ioe);
			}
		} catch (Throwable t) {
			if (Log.isLoggable("InstantRun", 6)) {
				Log.e("InstantRun", "Unexpected checksum exception", t);
			}
		}
		return null;
	}

	public static byte[] readRawBytes(File source) {
		try {
			if (Log.isLoggable("InstantRun", 2)) {
				Log.v("InstantRun", "Reading the bytes for file " + source);
			}
			long length = source.length();
			if (length > 2147483647L) {
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun", "File too large (" + length + ")");
				}
				return null;
			}
			byte[] result = new byte[(int) length];

			BufferedInputStream input = new BufferedInputStream(
					new FileInputStream(source));
			try {
				int index = 0;
				int remaining = result.length - index;
				int numRead;
				while (remaining > 0) {
					numRead = input.read(result, index, remaining);
					if (numRead == -1) {
						break;
					}
					index += numRead;
					remaining -= numRead;
				}
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun", "Returning length " + result.length
							+ " for file " + source);
				}
				return result;
			} finally {
				input.close();
			}
		} catch (IOException ioe) {
			if (Log.isLoggable("InstantRun", 6)) {
				Log.e("InstantRun", "Failed to read file " + source, ioe);
			}
			if (Log.isLoggable("InstantRun", 2)) {
				Log.v("InstantRun", "I/O error, no bytes returned for "
						+ source);
			}
		}
		return null;
	}
}
