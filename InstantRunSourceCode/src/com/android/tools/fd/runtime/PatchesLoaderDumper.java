package com.android.tools.fd.runtime;

public class PatchesLoaderDumper {
	public static void main(String[] args) {
		try {
			Class<?> aClass = Class
					.forName("com.android.tools.fd.runtime.AppPatchesLoaderImpl");
			PatchesLoader patchesLoader = (PatchesLoader) aClass.newInstance();
			patchesLoader.load();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} catch (InstantiationException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		}
	}
}
