package com.hackforsweden.touchmenot

class KalmanFilter {
    private var R /* Process Noise */: Double
    private var Q /* Measurement Noise */: Double
    private var A: Double
    private var B: Double
    private var C: Double
    private var cov = 0.0
    private var x /* Measurement */ = 0.0
    private var predX = 0.0
    private var predCov = 0.0
    private var K = 0.0

    internal constructor(
        R: Double,
        Q: Double,
        A: Double,
        B: Double,
        C: Double
    ) {
        this.R = R
        this.Q = Q
        this.A = A
        this.B = B
        this.C = C
    }

    internal constructor() {
        R = 1.0
        Q = 1.0
        A = 1.0
        B = 0.0
        C = 1.0
    }

    fun filter(z: Double, u: Double): Double {
        if (x == 0.0) {
            x = 1 / C * z
            cov = 1 / C * Q * (1 / C)
        } else {
            predX = predict(u)
            predCov = uncertainty()
            // Kalman gain
            K = predCov * C * (1 / (C * predCov * C + Q))
            // Correction
            x = predX + K * (z - C * predX)
            cov = predCov - K * C * predCov
        }
        return x
    }

    fun predict(u: Double): Double {
        return A * x + B * u
    }

    fun uncertainty(): Double {
        return A * cov * A + R
    }

    fun setProcessNoise(noise: Double) {
        R = noise
    }

    fun lastMeasurement(): Double {
        return x
    }

    fun setMeasurementNoise(noise: Double) {
        Q = noise
    }
}
