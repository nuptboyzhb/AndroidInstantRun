package com.android.tools.fd.runtime;

public enum BasicType {
	I(Integer.TYPE), J(Long.TYPE), C(Character.TYPE), Z(Boolean.TYPE), F(
			Float.TYPE), D(Double.TYPE), V(Void.TYPE);

	private final Class<?> primitiveJavaType;

	private BasicType(Class<?> primitiveType) {
		this.primitiveJavaType = primitiveType;
	}

	public Class getJavaType() {
		return this.primitiveJavaType;
	}

	public static BasicType parse(String name) {
		for (BasicType basicType : BasicType.values()) {
			if (basicType.getJavaType().getName().equals(name)) {
				return basicType;
			}
		}
		return null;
	}
}