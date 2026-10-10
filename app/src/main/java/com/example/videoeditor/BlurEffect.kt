package com.example.videoeditor

import androidx.media3.effect.ConvolutionFunction1D
import androidx.media3.effect.GaussianFunction
import androidx.media3.effect.SeparableConvolution
import kotlin.math.max

/** Gaussian blur whose strength (sigma in pixels) can change over time using keyframes. */
class BlurEffect(
    private val param: Param,
    private val startUs: Long,
    private val speed: Float
) : SeparableConvolution() {
    override fun getConvolution(presentationTimeUs: Long): ConvolutionFunction1D {
        val tMs = ((presentationTimeUs - startUs) / 1000L / speed).toLong().coerceAtLeast(0L)
        val sigma = max(0.5f, param.at(tMs))
        return GaussianFunction(sigma, 2f)
    }
}
