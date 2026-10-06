package dev.hnmc.link;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

/** The few kernel32 calls we need, bound through the Java 25 Foreign Function API (no JNI). */
final class Win32 {
	private Win32() {}

	static final int PAGE_READWRITE = 0x04;
	static final int FILE_MAP_ALL_ACCESS = 0x000F001F;
	static final int ERROR_ALREADY_EXISTS = 183;
	static final MemorySegment INVALID_HANDLE_VALUE = MemorySegment.ofAddress(-1L);

	private static final Linker LINKER = Linker.nativeLinker();
	private static final SymbolLookup K32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
	private static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
	private static final VarHandle LAST_ERROR = CAPTURE.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));

	private static MethodHandle bind(String name, FunctionDescriptor fd, Linker.Option... opts) {
		return LINKER.downcallHandle(K32.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), fd, opts);
	}

	private static final MethodHandle CREATE_FILE_MAPPING = bind("CreateFileMappingA",
		FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
			ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
		Linker.Option.captureCallState("GetLastError"));
	private static final MethodHandle MAP_VIEW = bind("MapViewOfFile",
		FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
			ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
	private static final MethodHandle UNMAP_VIEW = bind("UnmapViewOfFile",
		FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
	private static final MethodHandle CLOSE_HANDLE = bind("CloseHandle",
		FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
	private static final MethodHandle TICK_COUNT_64 = bind("GetTickCount64",
		FunctionDescriptor.of(ValueLayout.JAVA_LONG));

	record Created(MemorySegment handle, boolean alreadyExisted) {}

	static Created createFileMapping(String name, long bytes) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment capture = arena.allocate(CAPTURE);
			MemorySegment nameStr = arena.allocateFrom(name);
			MemorySegment h = (MemorySegment) CREATE_FILE_MAPPING.invoke(capture, INVALID_HANDLE_VALUE, MemorySegment.NULL,
				PAGE_READWRITE, (int) (bytes >>> 32), (int) bytes, nameStr);
			int err = (int) LAST_ERROR.get(capture, 0L);
			if (h.equals(MemorySegment.NULL)) throw new IllegalStateException("CreateFileMappingA failed, error " + err);
			return new Created(h, err == ERROR_ALREADY_EXISTS);
		} catch (RuntimeException e) {
			throw e;
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	static MemorySegment mapView(MemorySegment handle, long bytes) {
		try {
			MemorySegment p = (MemorySegment) MAP_VIEW.invoke(handle, FILE_MAP_ALL_ACCESS, 0, 0, bytes);
			if (p.equals(MemorySegment.NULL)) throw new IllegalStateException("MapViewOfFile failed");
			return p.reinterpret(bytes);
		} catch (RuntimeException e) {
			throw e;
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	static void unmapView(MemorySegment view) {
		try {
			int ignored = (int) UNMAP_VIEW.invoke(view);
		} catch (Throwable ignored) {
			// best effort on shutdown
		}
	}

	static void closeHandle(MemorySegment handle) {
		try {
			int ignored = (int) CLOSE_HANDLE.invoke(handle);
		} catch (Throwable ignored) {
			// best effort on shutdown
		}
	}

	static long tickCount64() {
		try {
			return (long) TICK_COUNT_64.invoke();
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}
}
