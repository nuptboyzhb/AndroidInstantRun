package com.android.tools.fd.runtime;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.Log;
import android.widget.Toast;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class Restarter {
	public static void restartActivityOnUiThread(final Activity activity) {
		activity.runOnUiThread(new Runnable() {
			public void run() {
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun", "Resources updated: notify activities");
				}
				Restarter.updateActivity(activity);
			}
		});
	}

	private static void restartActivity(Activity activity) {
		if (Log.isLoggable("InstantRun", 2)) {
			Log.v("InstantRun", "About to restart "
					+ activity.getClass().getSimpleName());
		}
		while (activity.getParent() != null) {
			if (Log.isLoggable("InstantRun", 2)) {
				Log.v("InstantRun", activity.getClass().getSimpleName()
						+ " is not a top level activity; restarting "
						+ activity.getParent().getClass().getSimpleName()
						+ " instead");
			}
			activity = activity.getParent();
		}
		activity.recreate();
	}

	public static void restartApp(Context appContext,
			Collection<Activity> knownActivities, boolean toast) {
		if (!knownActivities.isEmpty()) {
			Activity foreground = getForegroundActivity(appContext);
			if (foreground != null) {
				if (toast) {
					showToast(foreground,
							"Restarting app to apply incompatible changes");
				}
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun", "RESTARTING APP");
				}
				Context context = foreground;
				Intent intent = new Intent(context, foreground.getClass());
				int intentId = 0;
				PendingIntent pendingIntent = PendingIntent.getActivity(
						context, intentId, intent, 268435456);

				AlarmManager mgr = (AlarmManager) context
						.getSystemService("alarm");
				mgr.set(1, System.currentTimeMillis() + 100L, pendingIntent);
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun", "Scheduling activity " + foreground
							+ " to start after exiting process");
				}
			} else {
				showToast((Activity) knownActivities.iterator().next(),
						"Unable to restart app");
				if (Log.isLoggable("InstantRun", 2)) {
					Log.v("InstantRun",
							"Couldn't find any foreground activities to restart for resource refresh");
				}
			}
			System.exit(0);
		}
	}

	static void showToast(final Activity activity, final String text) {
		if (Log.isLoggable("InstantRun", 2)) {
			Log.v("InstantRun", "About to show toast for activity " + activity
					+ ": " + text);
		}
		activity.runOnUiThread(new Runnable() {
			public void run() {
				try {
					Context context = activity.getApplicationContext();
					if ((context instanceof ContextWrapper)) {
						Context base = ((ContextWrapper) context)
								.getBaseContext();
						if (base == null) {
							if (Log.isLoggable("InstantRun", 5)) {
								Log.w("InstantRun",
										"Couldn't show toast: no base context");
							}
							return;
						}
					}
					int duration = 0;
					if ((text.length() >= 60) || (text.indexOf('\n') != -1)) {
						duration = 1;
					}
					Toast.makeText(activity, text, duration).show();
				} catch (Throwable e) {
					if (Log.isLoggable("InstantRun", 5)) {
						Log.w("InstantRun", "Couldn't show toast", e);
					}
				}
			}
		});
	}

	public static Activity getForegroundActivity(Context context) {
		List<Activity> list = getActivities(context, true);
		return list.isEmpty() ? null : (Activity) list.get(0);
	}

	@SuppressLint("NewApi")
	public static List<Activity> getActivities(Context context,
			boolean foregroundOnly) {
		List<Activity> list = new ArrayList();
		try {
			Class activityThreadClass = Class
					.forName("android.app.ActivityThread");
			Object activityThread = MonkeyPatcher.getActivityThread(context,
					activityThreadClass);
			Field activitiesField = activityThreadClass
					.getDeclaredField("mActivities");
			activitiesField.setAccessible(true);

			Object collection = activitiesField.get(activityThread);
			Collection c;
			if ((collection instanceof HashMap)) {
				Map activities = (HashMap) collection;
				c = activities.values();
			} else {
				if ((Build.VERSION.SDK_INT >= 19)
						&& ((collection instanceof ArrayMap))) {
					ArrayMap activities = (ArrayMap) collection;
					ArrayList aList = new ArrayList();
					for(Map.Entry<String,Object> entry:((ArrayMap<String,Object>)activities).entrySet()){
						aList.add(entry.getValue());
					}
					c = aList;
				} else {
					return list;
				}
			}
			for (Object activityRecord : c) {
				Class activityRecordClass = activityRecord.getClass();
				if (foregroundOnly) {
					Field pausedField = activityRecordClass
							.getDeclaredField("paused");
					pausedField.setAccessible(true);
					if (pausedField.getBoolean(activityRecord)) {
					}
				} else {
					Field activityField = activityRecordClass
							.getDeclaredField("activity");
					activityField.setAccessible(true);
					Activity activity = (Activity) activityField
							.get(activityRecord);
					if (activity != null) {
						list.add(activity);
					}
				}
			}
		} catch (Throwable ignore) {
		}
		return list;
	}

	private static void updateActivity(Activity activity) {
		restartActivity(activity);
	}

	public static void showToastWhenPossible(Context context, String message) {
		Activity activity = getForegroundActivity(context);
		if (activity != null) {
			showToast(activity, message);
		} else {
			showToastWhenPossible(context, message, 10);
		}
	}

	private static void showToastWhenPossible(final Context context,
			final String message, final int remainingAttempts) {
		Looper mainLooper = Looper.getMainLooper();
		Handler handler = new Handler(mainLooper);
		handler.postDelayed(new Runnable() {
			public void run() {
				Activity activity = Restarter
						.getForegroundActivity(context);
				if (activity != null) {
					Restarter.showToast(activity, message);
				} else if (remainingAttempts > 0) {
					Restarter.showToastWhenPossible(context, message,
							remainingAttempts - 1);
				}
			}
		}, 1000L);
	}
}
