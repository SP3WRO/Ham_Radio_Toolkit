/*
Mode interface

Copyright 2024 Ahmet Inan <xdsopl@gmail.com>
*/

package io.github.sp3wro.hamradiotoolkit.sstv.robot36;

import android.graphics.Bitmap;

public interface Mode {
	String getName();

	int getVISCode();

	int getWidth();

	int getHeight();

	int getFirstPixelSampleIndex();

	int getFirstSyncPulseIndex();

	int getScanLineSamples();

	Bitmap postProcessScopeImage(Bitmap bmp);

	void resetState();

	/**
	 * @param frequencyOffset normalized correction of frequency (expected vs actual)
	 * @return true if scanline was decoded
	 */
	boolean decodeScanLine(PixelBuffer pixelBuffer, float[] scratchBuffer, float[] scanLineBuffer, int scopeBufferWidth, int syncPulseIndex, int scanLineSamples, float frequencyOffset);
}
