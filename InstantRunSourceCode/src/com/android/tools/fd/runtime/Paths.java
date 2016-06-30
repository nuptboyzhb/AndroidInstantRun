package com.android.tools.fd.runtime;

public final class Paths {
	public static final String DEX_DIRECTORY_NAME = "dex";
	public static final String DEVICE_TEMP_DIR = "/data/local/tmp";
	public static final String BUILD_ID_TXT = "build-id.txt";
	public static final String RESOURCE_FILE_NAME = "resources.ap_";
	public static final String RELOAD_DEX_FILE_NAME = "classes.dex.3";
	public static final String DEX_SLICE_PREFIX = "slice-";

	public static String getMainApkDataDirectory(String applicationId) {
		return "/data/data/" + applicationId;
	}

	public static String getDataDirectory(String applicationId) {
		return "/data/data/" + applicationId + "/files/instant-run";
	}

	public static String getDexFileDirectory(String applicationId) {
		return getDataDirectory(applicationId) + "/" + "dex";
	}

	public static String getInboxDirectory(String applicationId) {
		return getDataDirectory(applicationId) + "/inbox";
	}

	public static String getDeviceIdFolder(String pkg) {
		return "/data/local/tmp/" + pkg + "-" + "build-id.txt";
	}
}