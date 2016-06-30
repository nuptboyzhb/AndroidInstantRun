package com.android.tools.fd.runtime;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import com.android.tools.fd.common.Log;

public class ApplicationPatch {
	public final String path;
	public final byte[] data;

	public ApplicationPatch(String path, byte[] data) {
		this.path = path;
		this.data = data;
	}

	public String toString() {
		return "ApplicationPatch{path='" + this.path + '\'' + ", data.length='"
				+ this.data.length + '\'' + '}';
	}

	public static List<ApplicationPatch> read(DataInputStream input)
			throws IOException {
		int changeCount = input.readInt();
		if ((Log.logging != null) && (Log.logging.isLoggable(Level.FINE))) {
			Log.logging
					.log(Level.FINE, "Receiving " + changeCount + " changes");
		}
		List<ApplicationPatch> changes = new ArrayList<ApplicationPatch>(changeCount);
		for (int i = 0; i < changeCount; i++) {
			String path = input.readUTF();
			int size = input.readInt();
			byte[] bytes = new byte[size];
			input.readFully(bytes);
			changes.add(new ApplicationPatch(path, bytes));
		}
		return changes;
	}

	public String getPath() {
		return this.path;
	}

	public byte[] getBytes() {
		return this.data;
	}
}
