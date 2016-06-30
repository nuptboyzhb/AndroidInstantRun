package com.android.tools.fd.runtime;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.util.ArrayMap;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.SparseArray;
import android.view.ContextThemeWrapper;

@SuppressLint("NewApi")
public class MonkeyPatcher {
	
	
	/**
	 * 用realApplication替换原有的application
	 * @param context
	 * @param bootstrap
	 * @param realApplication
	 * @param externalResourceFile
	 */
	public static void monkeyPatchApplication(Context context,
			Application bootstrap, Application realApplication,
			String externalResourceFile) {
		try {
			Class<?> activityThread = Class
					.forName("android.app.ActivityThread");
			Object currentActivityThread = getActivityThread(context,
					activityThread);

			Field mInitialApplication = activityThread
					.getDeclaredField("mInitialApplication");
			mInitialApplication.setAccessible(true);
			Application initialApplication = (Application) mInitialApplication
					.get(currentActivityThread);
			if ((realApplication != null) && (initialApplication == bootstrap)) {
				mInitialApplication.set(currentActivityThread, realApplication);
			}
			if (realApplication != null) {
				Field mAllApplications = activityThread
						.getDeclaredField("mAllApplications");
				mAllApplications.setAccessible(true);
				List<Application> allApplications = (List<Application>) mAllApplications
						.get(currentActivityThread);
				for (int i = 0; i < allApplications.size(); i++) {
					if (allApplications.get(i) == bootstrap) {
						allApplications.set(i, realApplication);
					}
				}
			}
			Class<?> loadedApkClass;
			try {
				loadedApkClass = Class.forName("android.app.LoadedApk");
			} catch (ClassNotFoundException e) {
				loadedApkClass = Class
						.forName("android.app.ActivityThread$PackageInfo");
			}
			Field mApplication = loadedApkClass
					.getDeclaredField("mApplication");
			mApplication.setAccessible(true);
			Field mResDir = loadedApkClass.getDeclaredField("mResDir");
			mResDir.setAccessible(true);

			Field mLoadedApk = null;
			try {
				mLoadedApk = Application.class.getDeclaredField("mLoadedApk");
			} catch (NoSuchFieldException e) {
			}
			for (String fieldName : new String[] { "mPackages",
					"mResourcePackages" }) {
				Field field = activityThread.getDeclaredField(fieldName);
				field.setAccessible(true);
				Object value = field.get(currentActivityThread);
				for (Map.Entry<String, WeakReference<?>> entry : ((Map<String, WeakReference<?>>) value)
						.entrySet()) {
					Object loadedApk = ((WeakReference) entry.getValue()).get();
					if (loadedApk != null) {
						if (mApplication.get(loadedApk) == bootstrap) {
							if (realApplication != null) {
								mApplication.set(loadedApk, realApplication);
							}
							if (externalResourceFile != null) {
								mResDir.set(loadedApk, externalResourceFile);
							}
							if ((realApplication != null)
									&& (mLoadedApk != null)) {
								mLoadedApk.set(realApplication, loadedApk);
							}
						}
					}
				}
			}
		} catch (Throwable e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * 获取当前的ActivityThread对象
	 * 
	 * @param context
	 * @param activityThread
	 * @return
	 */
	public static Object getActivityThread(Context context,
			Class<?> activityThread) {
		try {
			if (activityThread == null) {
				activityThread = Class.forName("android.app.ActivityThread");
			}
			Method m = activityThread.getMethod("currentActivityThread",
					new Class[0]);
			m.setAccessible(true);
			Object currentActivityThread = m.invoke(activityThread,
					new Object[0]);
			Object apk = null;
			Field mActivityThreadField = null;
			if ((currentActivityThread == null) && (context != null)) {
				Field mLoadedApk = context.getClass().getField("mLoadedApk");
				mLoadedApk.setAccessible(true);
				apk = mLoadedApk.get(context);
				mActivityThreadField = apk.getClass().getDeclaredField(
						"mActivityThread");
				mActivityThreadField.setAccessible(true);
			}
			return mActivityThreadField.get(apk);
		} catch (Throwable ignore) {
		}
		return null;
	}

	public static void monkeyPatchExistingResources(Context context,
			String externalResourceFile, Collection<Activity> activities) {
		if (externalResourceFile == null) {
			return;
		}
		try {
			AssetManager newAssetManager = (AssetManager) AssetManager.class
					.getConstructor(new Class[0]).newInstance(new Object[0]);
			Method mAddAssetPath = AssetManager.class.getDeclaredMethod(
					"addAssetPath", new Class[] { String.class });
			mAddAssetPath.setAccessible(true);
			if (((Integer) mAddAssetPath.invoke(newAssetManager,
					new Object[] { externalResourceFile })).intValue() == 0) {
				throw new IllegalStateException(
						"Could not create new AssetManager");
			}
			Method mEnsureStringBlocks = AssetManager.class.getDeclaredMethod(
					"ensureStringBlocks", new Class[0]);
			mEnsureStringBlocks.setAccessible(true);
			mEnsureStringBlocks.invoke(newAssetManager, new Object[0]);
			if (activities != null) {
				for (Activity activity : activities) {
					Resources resources = activity.getResources();
					try {
						Field mAssets = Resources.class
								.getDeclaredField("mAssets");
						mAssets.setAccessible(true);
						mAssets.set(resources, newAssetManager);
					} catch (Throwable ignore) {
						Field mResourcesImpl = Resources.class
								.getDeclaredField("mResourcesImpl");
						mResourcesImpl.setAccessible(true);
						Object resourceImpl = mResourcesImpl.get(resources);
						Field implAssets = resourceImpl.getClass()
								.getDeclaredField("mAssets");
						implAssets.setAccessible(true);
						implAssets.set(resourceImpl, newAssetManager);
					}
					Resources.Theme theme = activity.getTheme();
					try {
						try {
							Field ma = Resources.Theme.class
									.getDeclaredField("mAssets");
							ma.setAccessible(true);
							ma.set(theme, newAssetManager);
						} catch (NoSuchFieldException ignore) {
							Field themeField = Resources.Theme.class
									.getDeclaredField("mThemeImpl");
							themeField.setAccessible(true);
							Object impl = themeField.get(theme);
							Field ma = impl.getClass().getDeclaredField(
									"mAssets");
							ma.setAccessible(true);
							ma.set(impl, newAssetManager);
						}
						Field mt = ContextThemeWrapper.class
								.getDeclaredField("mTheme");
						mt.setAccessible(true);
						mt.set(activity, null);
						Method mtm = ContextThemeWrapper.class
								.getDeclaredMethod("initializeTheme",
										new Class[0]);
						mtm.setAccessible(true);
						mtm.invoke(activity, new Object[0]);

						Method mCreateTheme = AssetManager.class
								.getDeclaredMethod("createTheme", new Class[0]);
						mCreateTheme.setAccessible(true);
						Object internalTheme = mCreateTheme.invoke(
								newAssetManager, new Object[0]);
						Field mTheme = Resources.Theme.class
								.getDeclaredField("mTheme");
						mTheme.setAccessible(true);
						mTheme.set(theme, internalTheme);
					} catch (Throwable e) {
						Log.e("InstantRun",
								"Failed to update existing theme for activity "
										+ activity, e);
					}
					pruneResourceCaches(resources);
				}
			}
			Collection<WeakReference<Resources>> references;
			if (Build.VERSION.SDK_INT >= 19) {
				Class<?> resourcesManagerClass = Class
						.forName("android.app.ResourcesManager");
				Method mGetInstance = resourcesManagerClass.getDeclaredMethod(
						"getInstance", new Class[0]);
				mGetInstance.setAccessible(true);
				Object resourcesManager = mGetInstance.invoke(null,
						new Object[0]);
				try {
					Field fMActiveResources = resourcesManagerClass
							.getDeclaredField("mActiveResources");
					fMActiveResources.setAccessible(true);

					ArrayMap<?, WeakReference<Resources>> arrayMap = (ArrayMap) fMActiveResources
							.get(resourcesManager);
					references = arrayMap.values();
				} catch (NoSuchFieldException ignore) {
					Field mResourceReferences = resourcesManagerClass
							.getDeclaredField("mResourceReferences");
					mResourceReferences.setAccessible(true);

					references = (Collection) mResourceReferences
							.get(resourcesManager);
				}
			} else {
				Class<?> activityThread = Class
						.forName("android.app.ActivityThread");
				Field fMActiveResources = activityThread
						.getDeclaredField("mActiveResources");
				fMActiveResources.setAccessible(true);
				Object thread = getActivityThread(context, activityThread);

				HashMap<?, WeakReference<Resources>> map = (HashMap) fMActiveResources
						.get(thread);

				references = map.values();
			}
			for (WeakReference<Resources> wr : references) {
				Resources resources = (Resources) wr.get();
				if (resources != null) {
					try {
						Field mAssets = Resources.class
								.getDeclaredField("mAssets");
						mAssets.setAccessible(true);
						mAssets.set(resources, newAssetManager);
					} catch (Throwable ignore) {
						Field mResourcesImpl = Resources.class
								.getDeclaredField("mResourcesImpl");
						mResourcesImpl.setAccessible(true);
						Object resourceImpl = mResourcesImpl.get(resources);
						Field implAssets = resourceImpl.getClass()
								.getDeclaredField("mAssets");
						implAssets.setAccessible(true);
						implAssets.set(resourceImpl, newAssetManager);
					}
					resources.updateConfiguration(resources.getConfiguration(),
							resources.getDisplayMetrics());
				}
			}
		} catch (Throwable e) {
			throw new IllegalStateException(e);
		}
	}

	private static void pruneResourceCaches(Object resources) {
		if (Build.VERSION.SDK_INT >= 21) {
			try {
				Field typedArrayPoolField = Resources.class
						.getDeclaredField("mTypedArrayPool");

				typedArrayPoolField.setAccessible(true);
				Object pool = typedArrayPoolField.get(resources);
				Class<?> poolClass = pool.getClass();
				Method acquireMethod = poolClass.getDeclaredMethod("acquire",
						new Class[0]);
				acquireMethod.setAccessible(true);
				for (;;) {
					Object typedArray = acquireMethod.invoke(pool,
							new Object[0]);
					if (typedArray == null) {
						break;
					}
				}
			} catch (Throwable ignore) {
			}
		}
		if (Build.VERSION.SDK_INT >= 23) {
			try {
				Field mResourcesImpl = Resources.class
						.getDeclaredField("mResourcesImpl");
				mResourcesImpl.setAccessible(true);

				resources = mResourcesImpl.get(resources);
			} catch (Throwable ignore) {
			}
		}
		Object lock = null;
		if (Build.VERSION.SDK_INT >= 18) {
			try {
				Field field = resources.getClass().getDeclaredField(
						"mAccessLock");
				field.setAccessible(true);
				lock = field.get(resources);
			} catch (Throwable ignore) {
			}
		} else {
			try {
				Field field = Resources.class.getDeclaredField("mTmpValue");
				field.setAccessible(true);
				lock = field.get(resources);
			} catch (Throwable ignore) {
			}
		}
		if (lock == null) {
			lock = MonkeyPatcher.class;
		}
		synchronized (lock) {
			pruneResourceCache(resources, "mDrawableCache");
			pruneResourceCache(resources, "mColorDrawableCache");
			pruneResourceCache(resources, "mColorStateListCache");
			if (Build.VERSION.SDK_INT >= 23) {
				pruneResourceCache(resources, "mAnimatorCache");
				pruneResourceCache(resources, "mStateListAnimatorCache");
			}
		}
	}

	private static boolean pruneResourceCache(Object resources, String fieldName) {
		try {
			Class<?> resourcesClass = resources.getClass();
			Field cacheField;
			try {
				cacheField = resourcesClass.getDeclaredField(fieldName);
			} catch (NoSuchFieldException ignore) {
				cacheField = Resources.class.getDeclaredField(fieldName);
			}
			cacheField.setAccessible(true);
			Object cache = cacheField.get(resources);

			Class<?> type = cacheField.getType();
			if (Build.VERSION.SDK_INT < 16) {
				if ((cache instanceof SparseArray)) {
					((SparseArray) cache).clear();
					return true;
				}
				if ((Build.VERSION.SDK_INT >= 14)
						&& ((cache instanceof LongSparseArray))) {
					((LongSparseArray) cache).clear();
					return true;
				}
			} else if (Build.VERSION.SDK_INT < 23) {
				if ("mColorStateListCache".equals(fieldName)) {
					if ((cache instanceof LongSparseArray)) {
						((LongSparseArray) cache).clear();
					}
				} else {
					if (type.isAssignableFrom(ArrayMap.class)) {
						Method clearArrayMap = Resources.class
								.getDeclaredMethod("clearDrawableCachesLocked",
										new Class[] { ArrayMap.class,
												Integer.TYPE });

						clearArrayMap.setAccessible(true);
						clearArrayMap.invoke(resources, new Object[] { cache,
								Integer.valueOf(-1) });
						return true;
					}
					if (type.isAssignableFrom(LongSparseArray.class)) {
						Method clearSparseMap = Resources.class
								.getDeclaredMethod("clearDrawableCachesLocked",
										new Class[] { LongSparseArray.class,
												Integer.TYPE });

						clearSparseMap.setAccessible(true);
						clearSparseMap.invoke(resources, new Object[] { cache,
								Integer.valueOf(-1) });
						return true;
					}
				}
			} else {
				while (type != null) {
					try {
						Method configChangeMethod = type.getDeclaredMethod(
								"onConfigurationChange",
								new Class[] { Integer.TYPE });

						configChangeMethod.setAccessible(true);
						configChangeMethod.invoke(cache,
								new Object[] { Integer.valueOf(-1) });
						return true;
					} catch (Throwable ignore) {
					}
					type = type.getSuperclass();
				}
			}
		} catch (Throwable ignore) {
		}
		return false;
	}
}
