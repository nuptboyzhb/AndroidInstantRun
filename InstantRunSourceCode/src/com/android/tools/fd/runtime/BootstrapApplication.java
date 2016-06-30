package com.android.tools.fd.runtime;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Level;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Process;
import android.util.Log;

import com.android.tools.fd.common.Log.Logging;

public class BootstrapApplication extends Application {
	public static final String LOG_TAG = "InstantRun";
	private String externalResourcePath;
	private Application realApplication;

	static {
		com.android.tools.fd.common.Log.logging = new Logging() {

			@Override
			public void log(Level paramLevel, String paramString) {
				// TODO Auto-generated method stub

			}

			@Override
			public boolean isLoggable(Level paramLevel) {
				// TODO Auto-generated method stub
				return false;
			}

			@Override
			public void log(Level paramLevel, String paramString,
					Throwable paramThrowable) {
				// TODO Auto-generated method stub

			}

		};
	}

	public BootstrapApplication() {
		if (Log.isLoggable("InstantRun", 2)) {
			Log.v("InstantRun",
					String.format(
							"BootstrapApplication created. Android package is %s, real application class is %s.",
							new Object[] { AppInfo.applicationId,
									AppInfo.applicationClass }));
		}
	}

	private void createResources(long apkModified) {
		FileManager.checkInbox();

		File file = FileManager.getExternalResourceFile();
		this.externalResourcePath = (file != null ? file.getPath() : null);
		if (Log.isLoggable("InstantRun", 2)) {
			Log.v("InstantRun", "Resource override is "
					+ this.externalResourcePath);
		}
		if (file != null) {
			try {
				long resourceModified = file.lastModified();
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun", "Resource patch last modified: "
							+ resourceModified);
					Log.v("InstantRun", "APK last modified: " + apkModified
							+ " "
							+ (apkModified > resourceModified ? ">" : "<")
							+ " resource patch");
				}
				if ((apkModified == 0L) || (resourceModified <= apkModified)) {
					if (Log.isLoggable("InstantRun", 2)) {
						Log.v("InstantRun",
								"Ignoring resource file, older than APK");
					}
					this.externalResourcePath = null;
				}
			} catch (Throwable t) {
				Log.e("InstantRun", "Failed to check patch timestamps", t);
			}
		}
	}

	private static void setupClassLoaders(Context context, String codeCacheDir,
			long apkModified) {
		List<String> dexList = FileManager.getDexList(context, apkModified);

		Class<Server> server = Server.class;
		Class<MonkeyPatcher> patcher = MonkeyPatcher.class;
		if (!dexList.isEmpty()) {
			if (Log.isLoggable("InstantRun", 2)) {
				Log.v("InstantRun", "Bootstrapping class loader with dex list "
						+ join('\n', dexList));
			}
			ClassLoader classLoader = BootstrapApplication.class
					.getClassLoader();
			String nativeLibraryPath;
			try {
				nativeLibraryPath = (String) classLoader.getClass()
						.getMethod("getLdLibraryPath", new Class[0])
						.invoke(classLoader, new Object[0]);
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun", "Native library path: "
							+ nativeLibraryPath);
				}
			} catch (Throwable t) {
				Log.e("InstantRun", "Failed to determine native library path "
						+ t.getMessage());
				nativeLibraryPath = FileManager.getNativeLibraryFolder()
						.getPath();
			}
			IncrementalClassLoader.inject(classLoader, nativeLibraryPath,
					codeCacheDir, dexList);
		}
	}

	public static String join(char on, List<String> list) {
		StringBuilder stringBuilder = new StringBuilder();
		for (String item : list) {
			stringBuilder.append(item).append(on);
		}
		stringBuilder.deleteCharAt(stringBuilder.length() - 1);
		return stringBuilder.toString();
	}

	private void createRealApplication() {
		if (AppInfo.applicationClass != null) {
			if (Log.isLoggable("InstantRun", 2)) {
				Log.v("InstantRun",
						"About to create real application of class name = "
								+ AppInfo.applicationClass);
			}
			try {
				Class<? extends Application> realClass = (Class<? extends Application>) Class
						.forName(AppInfo.applicationClass);
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun",
							"Created delegate app class successfully : "
									+ realClass + " with class loader "
									+ realClass.getClassLoader());
				}
				Constructor<? extends Application> constructor = realClass
						.getConstructor(new Class[0]);
				this.realApplication = ((Application) constructor
						.newInstance(new Object[0]));
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun",
							"Created real app instance successfully :"
									+ this.realApplication);
				}
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		} else {
			this.realApplication = new Application();
		}
	}

	protected void attachBaseContext(Context context) {
		if (!AppInfo.usingApkSplits) {
			String apkFile = context.getApplicationInfo().sourceDir;
			long apkModified = apkFile != null ? new File(apkFile)
					.lastModified() : 0L;
			createResources(apkModified);
			setupClassLoaders(context, context.getCacheDir().getPath(),
					apkModified);
		}
		createRealApplication();

		super.attachBaseContext(context);
		if (this.realApplication != null) {
			try {
				Method attachBaseContext = ContextWrapper.class
						.getDeclaredMethod("attachBaseContext",
								new Class[] { Context.class });

				attachBaseContext.setAccessible(true);
				attachBaseContext.invoke(this.realApplication,
						new Object[] { context });
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}

	public void onCreate() {
		if (!AppInfo.usingApkSplits) {
			MonkeyPatcher.monkeyPatchApplication(this, this,
					this.realApplication, this.externalResourcePath);

			MonkeyPatcher.monkeyPatchExistingResources(this,
					this.externalResourcePath, null);
		} else {
			MonkeyPatcher.monkeyPatchApplication(this, this,
					this.realApplication, null);
		}
		super.onCreate();
		if (AppInfo.applicationId != null) {
			try {
				boolean foundPackage = false;
				int pid = Process.myPid();
				ActivityManager manager = (ActivityManager) getSystemService("activity");

				List<ActivityManager.RunningAppProcessInfo> processes = manager
						.getRunningAppProcesses();
				boolean startServer = false;
				if ((processes != null) && (processes.size() > 1)) {
					for (ActivityManager.RunningAppProcessInfo processInfo : processes) {
						if (AppInfo.applicationId
								.equals(processInfo.processName)) {
							foundPackage = true;
							if (processInfo.pid == pid) {
								startServer = true;
								break;
							}
						}
					}
					if ((!startServer) && (!foundPackage)) {
						startServer = true;
						if (Log.isLoggable("InstantRun", 2)) {
							Log.v("InstantRun",
									"Multiprocess but didn't find process with package: starting server anyway");
						}
					}
				} else {
					startServer = true;
				}
				if (startServer) {
					Server.create(AppInfo.applicationId, this);
				}
			} catch (Throwable t) {
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun", "Failed during multi process check", t);
				}
				Server.create(AppInfo.applicationId, this);
			}
		}
		if (this.realApplication != null) {
			this.realApplication.onCreate();
		}
	}
}
