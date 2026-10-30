/*
Base class for all modes

Copyright 2025 Marek Ossowski <marek0ossowski@gmail.com>
*/

package io.github.sp3wro.hamradiotoolkit.sstv.robot36;

import android.graphics.Bitmap;

public abstract class BaseMode implements Mode {
	@Override
	public Bitmap postProcessScopeImage(Bitmap bmp) {
		return bmp;
	}
}
