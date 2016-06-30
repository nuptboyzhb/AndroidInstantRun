package com.android.tools.fd.runtime;

import android.util.Log;
import dalvik.system.BaseDexClassLoader;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;

public class IncrementalClassLoader extends ClassLoader {
	public static final boolean DEBUG_CLASS_LOADING = false;
	private final DelegateClassLoader delegateClassLoader;

	public IncrementalClassLoader(ClassLoader original,
			String nativeLibraryPath, String codeCacheDir, List<String> dexes) {
		super(original.getParent());

		this.delegateClassLoader = createDelegateClassLoader(nativeLibraryPath,
				codeCacheDir, dexes, original);
	}

	public Class<?> findClass(String className) throws ClassNotFoundException {
		try {
			return this.delegateClassLoader.findClass(className);
		} catch (ClassNotFoundException e) {
			throw e;
		}
	}

	private static class DelegateClassLoader extends BaseDexClassLoader {

		private DelegateClassLoader(String dexPath, File optimizedDirectory,
				String libraryPath, ClassLoader parent) {
			super(dexPath, optimizedDirectory, libraryPath, parent);
		}

		public Class<?> findClass(String name) throws ClassNotFoundException {
			try {
				return super.findClass(name);
			} catch (ClassNotFoundException e) {
				throw e;
			}
		}
	}

	private static DelegateClassLoader createDelegateClassLoader(
			String nativeLibraryPath, String codeCacheDir, List<String> dexes,
			ClassLoader original) {
		String pathBuilder = createDexPath(dexes);
		return new DelegateClassLoader(pathBuilder, new File(codeCacheDir),
				nativeLibraryPath, original);
	}

	private static String createDexPath(List<String> dexes) {
		StringBuilder pathBuilder = new StringBuilder();
		boolean first = true;
		for (String dex : dexes) {
			if (first) {
				first = false;
			} else {
				pathBuilder.append(File.pathSeparator);
			}
			pathBuilder.append(dex);
		}
		if (Log.isLoggable("InstantRun", 2)) {
			Log.v("InstantRun", "Incremental dex path is "
					+ BootstrapApplication.join('\n', dexes));
		}
		return pathBuilder.toString();
	}

	private static void setParent(ClassLoader classLoader, ClassLoader newParent) {
		try {
			Field parent = ClassLoader.class.getDeclaredField("parent");
			parent.setAccessible(true);
			parent.set(classLoader, newParent);
		} catch (IllegalArgumentException e) {
			throw new RuntimeException(e);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		} catch (NoSuchFieldException e) {
			throw new RuntimeException(e);
		}
	}

	public static ClassLoader inject(ClassLoader classLoader,
			String nativeLibraryPath, String codeCacheDir, List<String> dexes) {
		IncrementalClassLoader incrementalClassLoader = new IncrementalClassLoader(
				classLoader, nativeLibraryPath, codeCacheDir, dexes);

		setParent(classLoader, incrementalClassLoader);

		return incrementalClassLoader;
	}
}
