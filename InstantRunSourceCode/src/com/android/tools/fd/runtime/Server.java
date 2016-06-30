package com.android.tools.fd.runtime;

import android.app.Activity;
import android.app.Application;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;
import dalvik.system.DexClassLoader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.List;

public class Server {
	private static final boolean RESTART_LOCALLY = false;
	private static final boolean POST_ALIVE_STATUS = false;
	private LocalServerSocket mServerSocket;
	private final Application mApplication;
	private static int sWrongTokenCount;

	public static void create(String packageName, Application application) {
		new Server(packageName, application);
	}

	private Server(String packageName, Application application) {
		this.mApplication = application;
		try {
			this.mServerSocket = new LocalServerSocket(packageName);
			if (Log.isLoggable("InstantRun", 2)) {
				Log.v("InstantRun",
						"Starting server socket listening for package "
								+ packageName + " on "
								+ this.mServerSocket.getLocalSocketAddress());
			}
		} catch (IOException e) {
			Log.e("InstantRun", "IO Error creating local socket at "
					+ packageName, e);
			return;
		}
		startServer();
		if (Log.isLoggable("InstantRun", 2)) {
			Log.v("InstantRun", "Started server for package " + packageName);
		}
	}

	private void startServer() {
		try {
			Thread socketServerThread = new Thread(new SocketServerThread());
			socketServerThread.start();
		} catch (Throwable e) {
			if (Log.isLoggable("InstantRun", 6)) {
				Log.e("InstantRun", "Fatal error starting Instant Run server",
						e);
			}
		}
	}

	private class SocketServerThread extends Thread {
		private SocketServerThread() {
		}

		public void run() {
			try {
				for (;;) {
					LocalServerSocket serverSocket = Server.this.mServerSocket;
					if (serverSocket == null) {
						break;
					}
					LocalSocket socket = serverSocket.accept();
					if (Log.isLoggable("InstantRun", 2)) {
						Log.v("InstantRun",
								"Received connection from IDE: spawning connection thread");
					}
					Server.SocketServerReplyThread socketServerReplyThread = new Server.SocketServerReplyThread(
							socket);

					socketServerReplyThread.run();
					if (Server.sWrongTokenCount > 50) {
						if (Log.isLoggable("InstantRun", 2)) {
							Log.v("InstantRun",
									"Stopping server: too many wrong token connections");
						}
						Server.this.mServerSocket.close();
						break;
					}
				}
			} catch (Throwable e) {
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun",
							"Fatal error accepting connection on local socket",
							e);
				}
			}
		}
	}

	private class SocketServerReplyThread extends Thread {
		private final LocalSocket mSocket;

		SocketServerReplyThread(LocalSocket socket) {
			this.mSocket = socket;
		}

		public void run() {
			try {
				DataInputStream input = new DataInputStream(
						this.mSocket.getInputStream());
				DataOutputStream output = new DataOutputStream(
						this.mSocket.getOutputStream());
				try {
					handle(input, output);
				} finally {
					try {
						input.close();
					} catch (IOException ignore) {
					}
					try {
						output.close();
					} catch (IOException ignore) {
					}
				}
				return;
			} catch (IOException e) {
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun", "Fatal error receiving messages", e);
				}
			}
		}

		private void handle(DataInputStream input, DataOutputStream output)
				throws IOException {
			long magic = input.readLong();
			if (magic != 890269988L) {
				Log.w("InstantRun",
						"Unrecognized header format " + Long.toHexString(magic));

				return;
			}
			int version = input.readInt();

			output.writeInt(4);
			if (version != 4) {
				Log.w("InstantRun",
						"Mismatched protocol versions; app is using version 4 and tool is using version "
								+ version);
			} else {
				int message;
				for (;;) {
					message = input.readInt();
					switch (message) {
					case 7:
						if (Log.isLoggable("InstantRun", 2)) {
							Log.v("InstantRun", "Received EOF from the IDE");
						}
						return;
					case 2:
						boolean active = Restarter
								.getForegroundActivity(Server.this.mApplication) != null;
						output.writeBoolean(active);
						if (Log.isLoggable("InstantRun", 2)) {
							Log.v("InstantRun",
									"Received Ping message from the IDE; returned active = "
											+ active);
						}
						break;
					case 3:
						String path = input.readUTF();
						long size = FileManager.getFileSize(path);
						output.writeLong(size);
						if (Log.isLoggable("InstantRun", 2)) {
							Log.v("InstantRun", "Received path-exists(" + path
									+ ") from the " + "IDE; returned size="
									+ size);
						}
						break;
					case 4:
						long begin = System.currentTimeMillis();
						path = input.readUTF();
						byte[] checksum = FileManager.getCheckSum(path);
						if (checksum != null) {
							output.writeInt(checksum.length);
							output.write(checksum);
							if (Log.isLoggable("InstantRun", 2)) {
								long end = System.currentTimeMillis();
								String hash = new BigInteger(1, checksum)
										.toString(16);
								Log.v("InstantRun", "Received checksum(" + path
										+ ") from the " + "IDE: took "
										+ (end - begin) + "ms to compute "
										+ hash);
							}
						} else {
							output.writeInt(0);
							if (Log.isLoggable("InstantRun", 2)) {
								Log.v("InstantRun", "Received checksum(" + path
										+ ") from the "
										+ "IDE: returning <null>");
							}
						}
						break;
					case 5:
						if (!authenticate(input)) {
							return;
						}
						Activity activity = Restarter
								.getForegroundActivity(Server.this.mApplication);
						if (activity != null) {
							if (Log.isLoggable("InstantRun", 2)) {
								Log.v("InstantRun",
										"Restarting activity per user request");
							}
							Restarter.restartActivityOnUiThread(activity);
						}
						break;
					case 1:
						if (!authenticate(input)) {
							return;
						}
						List<ApplicationPatch> changes = ApplicationPatch
								.read(input);
						if (changes != null) {
							boolean hasResources = Server.hasResources(changes);
							int updateMode = input.readInt();
							updateMode = Server.this.handlePatches(changes,
									hasResources, updateMode);

							boolean showToast = input.readBoolean();

							output.writeBoolean(true);

							Server.this.restart(updateMode, hasResources,
									showToast);
						}
						break;
					case 6:
						String text = input.readUTF();
						Activity foreground = Restarter
								.getForegroundActivity(Server.this.mApplication);
						if (foreground != null) {
							Restarter.showToast(foreground, text);
						} else if (Log.isLoggable("InstantRun", 2)) {
							Log.v("InstantRun",
									"Couldn't show toast (no activity) : "
											+ text);
						}
						break;
					}
				}

			}
		}

		private boolean authenticate(DataInputStream input) throws IOException {
			long token = input.readLong();
			if (token != AppInfo.token) {
				Log.w("InstantRun",
						"Mismatched identity token from client; received "
								+ token + " and expected " + AppInfo.token);
				return true;
			}
			return true;
		}
	}

	private static boolean isResourcePath(String path) {
		return (path.equals("resources.ap_")) || (path.startsWith("res/"));
	}

	private static boolean hasResources(List<ApplicationPatch> changes) {
		for (ApplicationPatch change : changes) {
			String path = change.getPath();
			if (isResourcePath(path)) {
				return true;
			}
		}
		return false;
	}

	private int handlePatches(List<ApplicationPatch> changes,
			boolean hasResources, int updateMode) {
		if (hasResources) {
			FileManager.startUpdate();
		}
		for (ApplicationPatch change : changes) {
			String path = change.getPath();
			if (path.endsWith(".dex")) {
				handleColdSwapPatch(change);

				boolean canHotSwap = false;
				for (ApplicationPatch c : changes) {
					if (c.getPath().equals("classes.dex.3")) {
						canHotSwap = true;
						break;
					}
				}
				if (!canHotSwap) {
					updateMode = 3;
				}
			} else if (path.equals("classes.dex.3")) {
				updateMode = handleHotSwapPatch(updateMode, change);
			} else if (isResourcePath(path)) {
				updateMode = handleResourcePatch(updateMode, change, path);
			}
		}
		if (hasResources) {
			FileManager.finishUpdate(true);
		}
		return updateMode;
	}

	private static int handleResourcePatch(int updateMode,
			ApplicationPatch patch, String path) {
		if (Log.isLoggable("InstantRun", 2)) {
			Log.v("InstantRun", "Received resource changes (" + path + ")");
		}
		FileManager.writeAaptResources(path, patch.getBytes());

		updateMode = Math.max(updateMode, 2);
		return updateMode;
	}

	private int handleHotSwapPatch(int updateMode, ApplicationPatch patch) {
		if (Log.isLoggable("InstantRun", 2)) {
			Log.v("InstantRun", "Received incremental code patch");
		}
		try {
			String dexFile = FileManager.writeTempDexFile(patch.getBytes());
			if (dexFile == null) {
				Log.e("InstantRun", "No file to write the code to");
				return updateMode;
			}
			if (Log.isLoggable("InstantRun", 2)) {
				Log.v("InstantRun", "Reading live code from " + dexFile);
			}
			String nativeLibraryPath = FileManager.getNativeLibraryFolder()
					.getPath();
			DexClassLoader dexClassLoader = new DexClassLoader(dexFile,
					this.mApplication.getCacheDir().getPath(),
					nativeLibraryPath, getClass().getClassLoader());

			Class<?> aClass = Class.forName(
					"com.android.tools.fd.runtime.AppPatchesLoaderImpl", true,
					dexClassLoader);
			try {
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun", "Got the patcher class " + aClass);
				}
				PatchesLoader loader = (PatchesLoader) aClass.newInstance();
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun", "Got the patcher instance " + loader);
				}
				String[] getPatchedClasses = (String[]) aClass
						.getDeclaredMethod("getPatchedClasses", new Class[0])
						.invoke(loader, new Object[0]);
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun", "Got the list of classes ");
					for (String getPatchedClass : getPatchedClasses) {
						Log.v("InstantRun", "class " + getPatchedClass);
					}
				}
				if (!loader.load()) {
					updateMode = 3;
				}
			} catch (Exception e) {
				Log.e("InstantRun", "Couldn't apply code changes", e);
				e.printStackTrace();
				updateMode = 3;
			}
		} catch (Throwable e) {
			Log.e("InstantRun", "Couldn't apply code changes", e);
			updateMode = 3;
		}
		return updateMode;
	}

	private static void handleColdSwapPatch(ApplicationPatch patch) {
		if (patch.path.startsWith("slice-")) {
			File file = FileManager.writeDexShard(patch.getBytes(), patch.path);
			if (Log.isLoggable("InstantRun", 2)) {
				Log.v("InstantRun", "Received dex shard " + file);
			}
		}
	}

	private void restart(int updateMode, boolean incrementalResources,
			boolean toast) {
		if (Log.isLoggable("InstantRun", 2)) {
			Log.v("InstantRun", "Finished loading changes; update mode ="
					+ updateMode);
		}
		if ((updateMode == 0) || (updateMode == 1)) {
			if (Log.isLoggable("InstantRun", 2)) {
				Log.v("InstantRun", "Applying incremental code without restart");
			}
			if (toast) {
				Activity foreground = Restarter
						.getForegroundActivity(this.mApplication);
				if (foreground != null) {
					Restarter.showToast(foreground,
							"Applied code changes without activity restart");
				} else if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun",
							"Couldn't show toast: no activity found");
				}
			}
			return;
		}
		List<Activity> activities = Restarter.getActivities(this.mApplication,
				false);
		if ((incrementalResources) && (updateMode == 2)) {
			File file = FileManager.getExternalResourceFile();
			if (Log.isLoggable("InstantRun", 2)) {
				Log.v("InstantRun", "About to update resource file=" + file
						+ ", activities=" + activities);
			}
			if (file != null) {
				String resources = file.getPath();
				MonkeyPatcher.monkeyPatchApplication(this.mApplication, null,
						null, resources);
				MonkeyPatcher.monkeyPatchExistingResources(this.mApplication,
						resources, activities);
			} else {
				Log.e("InstantRun", "No resource file found to apply");
				updateMode = 3;
			}
		}
		Activity activity = Restarter.getForegroundActivity(this.mApplication);
		if (updateMode == 2) {
			if (activity != null) {
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun", "Restarting activity only!");
				}
				boolean handledRestart = false;
				try {
					Method method = activity.getClass().getMethod(
							"onHandleCodeChange", new Class[] { Long.TYPE });
					Object result = method.invoke(activity,
							new Object[] { Long.valueOf(0L) });
					if (Log.isLoggable("InstantRun", 2)) {
						Log.v("InstantRun", "Activity " + activity
								+ " provided manual restart method; return "
								+ result);
					}
					if (Boolean.TRUE.equals(result)) {
						handledRestart = true;
						if (toast) {
							Restarter.showToast(activity, "Applied changes");
						}
					}
				} catch (Throwable ignore) {
				}
				if (!handledRestart) {
					if (toast) {
						Restarter.showToast(activity,
								"Applied changes, restarted activity");
					}
					Restarter.restartActivityOnUiThread(activity);
				}
				return;
			}
			if (Log.isLoggable("InstantRun", 2)) {
				Log.v("InstantRun",
						"No activity found, falling through to do a full app restart");
			}
			updateMode = 3;
		}
		if (updateMode != 3) {
			if (Log.isLoggable("InstantRun", 6)) {
				Log.e("InstantRun", "Unexpected update mode: " + updateMode);
			}
			return;
		}
		if (Log.isLoggable("InstantRun", 2)) {
			Log.v("InstantRun",
					"Waiting for app to be killed and restarted by the IDE...");
		}
	}
}
