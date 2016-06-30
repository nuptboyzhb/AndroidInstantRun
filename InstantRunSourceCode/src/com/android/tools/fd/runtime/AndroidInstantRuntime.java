package com.android.tools.fd.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.android.tools.fd.common.Log;

public class AndroidInstantRuntime {
	public static void setLogger(Logger logger) {
		Log.logging = new Log.Logging() {

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

	public static Object getStaticPrivateField(Class targetClass,
			String fieldName) {
		return getPrivateField(null, targetClass, fieldName);
	}

	public static void setStaticPrivateField(Object value, Class targetClass,
			String fieldName) {
		setPrivateField(null, value, targetClass, fieldName);
	}

	public static void setPrivateField(Object targetObject, Object value,
			Class targetClass, String fieldName) {
		try {
			Field declaredField = getField(targetClass, fieldName);
			declaredField.set(targetObject, value);
		} catch (IllegalAccessException e) {
			if (Log.logging != null) {
				Log.logging.log(Level.SEVERE, String.format(
						"Exception during setPrivateField %s",
						new Object[] { fieldName }), e);
			}
			throw new RuntimeException(e);
		}
	}

	public static Object getPrivateField(Object targetObject,
			Class targetClass, String fieldName) {
		try {
			Field declaredField = getField(targetClass, fieldName);
			return declaredField.get(targetObject);
		} catch (IllegalAccessException e) {
			if (Log.logging != null) {
				Log.logging.log(Level.SEVERE, String.format(
						"Exception during%1$s getField %2$s", new Object[] {
								targetObject == null ? " static" : "",
								fieldName }), e);
			}
			throw new RuntimeException(e);
		}
	}

	private static Field getField(Class target, String name) {
		Field declareField = getFieldByName(target, name);
		if (declareField == null) {
			throw new RuntimeException(new NoSuchElementException(name));
		}
		declareField.setAccessible(true);
		return declareField;
	}

	public static Object invokeProtectedMethod(Object receiver,
			Object[] params, Class[] parameterTypes, String methodName)
			throws Throwable {
		if ((Log.logging != null) && (Log.logging.isLoggable(Level.FINE))) {
			Log.logging.log(
					Level.FINE,
					String.format("protectedMethod:%s on %s", new Object[] {
							methodName, receiver }));
		}
		try {
			Method toDispatchTo = getMethodByName(receiver.getClass(),
					methodName, parameterTypes);
			if (toDispatchTo == null) {
				throw new RuntimeException(
						new NoSuchMethodException(methodName));
			}
			toDispatchTo.setAccessible(true);
			return toDispatchTo.invoke(receiver, params);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		} catch (IllegalAccessException e) {
			Log.logging.log(Level.SEVERE,
					String.format("Exception while invoking %s",
							new Object[] { methodName }), e);
			throw new RuntimeException(e);
		}
	}

	public static Object invokeProtectedStaticMethod(Object[] params,
			Class[] parameterTypes, String methodName, Class receiverClass)
			throws Throwable {
		if ((Log.logging != null) && (Log.logging.isLoggable(Level.FINE))) {
			Log.logging.log(Level.FINE, String.format(
					"protectedStaticMethod:%s on %s", new Object[] {
							methodName, receiverClass.getName() }));
		}
		try {
			Method toDispatchTo = getMethodByName(receiverClass, methodName,
					parameterTypes);
			if (toDispatchTo == null) {
				throw new RuntimeException(new NoSuchMethodException(methodName
						+ " in class " + receiverClass.getName()));
			}
			toDispatchTo.setAccessible(true);
			return toDispatchTo.invoke(null, params);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		} catch (IllegalAccessException e) {
			Log.logging.log(Level.SEVERE,
					String.format("Exception while invoking %s",
							new Object[] { methodName }), e);
			throw new RuntimeException(e);
		}
	}

	public static <T> T newForClass(Object[] params, Class[] paramTypes,
			Class<T> targetClass) throws Throwable {
		Constructor declaredConstructor;
		try {
			declaredConstructor = targetClass
					.getDeclaredConstructor(paramTypes);
		} catch (NoSuchMethodException e) {
			Log.logging.log(Level.SEVERE,
					"Exception while resolving constructor", e);
			throw new RuntimeException(e);
		}
		declaredConstructor.setAccessible(true);
		try {
			return (T) targetClass
					.cast(declaredConstructor.newInstance(params));
		} catch (InvocationTargetException e) {
			throw e.getCause();
		} catch (InstantiationException e) {
			Log.logging.log(Level.SEVERE, String.format(
					"Exception while instantiating %s",
					new Object[] { targetClass }), e);
			throw new RuntimeException(e);
		} catch (IllegalAccessException e) {
			Log.logging.log(Level.SEVERE, String.format(
					"Exception while instantiating %s",
					new Object[] { targetClass }), e);
			throw new RuntimeException(e);
		}
	}

	private static Field getFieldByName(Class<?> aClass, String name) {
		if ((Log.logging != null) && (Log.logging.isLoggable(Level.FINE))) {
			Log.logging.log(
					Level.FINE,
					String.format("getFieldByName:%s in %s", new Object[] {
							name, aClass.getName() }));
		}
		Class<?> currentClass = aClass;
		while (currentClass != null) {
			try {
				return currentClass.getDeclaredField(name);
			} catch (NoSuchFieldException e) {
			}
			currentClass = currentClass.getSuperclass();
		}
		return null;
	}

	private static Method getMethodByName(Class<?> aClass, String name,
			Class[] paramTypes) {
		if (aClass == null) {
			return null;
		}
		Class<?> currentClass = aClass;
		while (currentClass != null) {
			try {
				return currentClass.getDeclaredMethod(name, paramTypes);
			} catch (NoSuchMethodException e) {
				currentClass = currentClass.getSuperclass();
				if (currentClass == null) {
					continue;
				}
			}
			if ((Log.logging != null) && (Log.logging.isLoggable(Level.FINE))) {
				Log.logging.log(Level.FINE, String.format(
						"getMethodByName:Looking in %s now",
						new Object[] { currentClass.getName() }));
			}
		}
		return null;
	}

	public static void trace(String s) {
		if (Log.logging != null) {
			Log.logging.log(Level.FINE, s);
		}
	}

	public static void trace(String s1, String s2) {
		if (Log.logging != null) {
			Log.logging.log(Level.FINE,
					String.format("%s %s", new Object[] { s1, s2 }));
		}
	}

	public static void trace(String s1, String s2, String s3) {
		if (Log.logging != null) {
			Log.logging.log(Level.FINE,
					String.format("%s %s %s", new Object[] { s1, s2, s3 }));
		}
	}

	public static void trace(String s1, String s2, String s3, String s4) {
		if (Log.logging != null) {
			Log.logging.log(Level.FINE, String.format("%s %s %s %s",
					new Object[] { s1, s2, s3, s4 }));
		}
	}

	protected static abstract interface Logging {
		public abstract void log(Level paramLevel, String paramString);

		public abstract boolean isLoggable(Level paramLevel);

		public abstract void log(Level paramLevel, String paramString,
				Throwable paramThrowable);
	}
}