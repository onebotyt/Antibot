//! Real-time Audio Stream Processing, Ring-Buffer & FFT Spectrum Analysis in Rust.
//!
//! Provides:
//! - Thread-safe circular `AudioRingBuffer` for lock-free/microsecond PCM sample streaming.
//! - In-place Radix-2 Cooley-Tukey FFT with Hanning windowing for spectral magnitude.
//! - Multi-band Equalizer filter-bank (sub-bass, bass, low-mid, mid, high-mid, presence, brilliance).
//! - Complete `AudioTelemetry` calculation for 120 FPS Jetpack Compose audio waveforms and equalizers.

use serde::{Deserialize, Serialize};
use std::f32::consts::PI;
use std::sync::atomic::{AtomicUsize, Ordering};

// ---------------------------------------------------------------------------
// 1. Thread-safe Circular Audio Ring-Buffer
// ---------------------------------------------------------------------------

pub struct AudioRingBuffer {
    buffer: Vec<i16>,
    capacity: usize,
    write_head: AtomicUsize,
    total_written: AtomicUsize,
}

impl AudioRingBuffer {
    pub fn new(capacity: usize) -> Self {
        let cap = capacity.max(256);
        Self {
            buffer: vec![0i16; cap],
            capacity: cap,
            write_head: AtomicUsize::new(0),
            total_written: AtomicUsize::new(0),
        }
    }

    /// Writes PCM samples into the circular ring buffer.
    pub fn write(&mut self, samples: &[i16]) -> usize {
        let count = samples.len();
        if count == 0 {
            return 0;
        }

        let mut head = self.write_head.load(Ordering::Relaxed);
        for &s in samples {
            self.buffer[head] = s;
            head = (head + 1) % self.capacity;
        }

        self.write_head.store(head, Ordering::Release);
        self.total_written.fetch_add(count, Ordering::Relaxed);
        count
    }

    /// Reads the most recent `count` samples up to the buffer capacity.
    pub fn read_latest(&self, count: usize) -> Vec<i16> {
        let n = count.min(self.capacity);
        if n == 0 {
            return Vec::new();
        }

        let head = self.write_head.load(Ordering::Acquire);
        let mut out = Vec::with_capacity(n);

        let start = (head + self.capacity - n) % self.capacity;
        for i in 0..n {
            let idx = (start + i) % self.capacity;
            out.push(self.buffer[idx]);
        }

        out
    }

    pub fn capacity(&self) -> usize {
        self.capacity
    }

    pub fn total_written(&self) -> usize {
        self.total_written.load(Ordering::Relaxed)
    }

    pub fn clear(&mut self) {
        self.buffer.fill(0);
        self.write_head.store(0, Ordering::Relaxed);
        self.total_written.store(0, Ordering::Relaxed);
    }
}

// ---------------------------------------------------------------------------
// 2. Radix-2 Cooley-Tukey FFT & Spectral Analysis
// ---------------------------------------------------------------------------

#[derive(Clone, Copy)]
struct Complex {
    re: f32,
    im: f32,
}

impl Complex {
    fn zero() -> Self {
        Self { re: 0.0, im: 0.0 }
    }

    fn add(self, other: Self) -> Self {
        Self {
            re: self.re + other.re,
            im: self.im + other.im,
        }
    }

    fn sub(self, other: Self) -> Self {
        Self {
            re: self.re - other.re,
            im: self.im - other.im,
        }
    }

    fn mul(self, other: Self) -> Self {
        Self {
            re: self.re * other.re - self.im * other.im,
            im: self.re * other.im + self.im * other.re,
        }
    }

    fn magnitude(self) -> f32 {
        (self.re * self.re + self.im * self.im).sqrt()
    }
}

/// Bit-reversal permutation for in-place Radix-2 FFT.
fn bit_reverse_permute(data: &mut [Complex]) {
    let n = data.len();
    let mut j = 0;
    for i in 0..n {
        if i < j {
            data.swap(i, j);
        }
        let mut bit = n >> 1;
        while j & bit != 0 {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
    }
}

/// In-place Radix-2 Cooley-Tukey FFT on a power-of-two buffer.
fn fft_radix2_in_place(data: &mut [Complex]) {
    let n = data.len();
    if n <= 1 || (n & (n - 1)) != 0 {
        return;
    }

    bit_reverse_permute(data);

    let mut len = 2;
    while len <= n {
        let angle = -2.0 * PI / (len as f32);
        let wlen = Complex {
            re: angle.cos(),
            im: angle.sin(),
        };

        let mut i = 0;
        while i < n {
            let mut w = Complex { re: 1.0, im: 0.0 };
            for j in 0..(len / 2) {
                let u = data[i + j];
                let v = data[i + j + len / 2].mul(w);
                data[i + j] = u.add(v);
                data[i + j + len / 2] = u.sub(v);
                w = w.mul(wlen);
            }
            i += len;
        }
        len <<= 1;
    }
}

/// Computes normalized frequency spectrum magnitudes using Radix-2 FFT with Hanning window.
/// Returns (magnitudes, dominant_frequency_hz) for the positive half-spectrum (0..Nyquist).
pub fn compute_fft_spectrum(
    samples: &[i16],
    sample_rate: u32,
    fft_size: usize,
) -> (Vec<f32>, f32) {
    let n = fft_size.next_power_of_two().clamp(64, 2048);
    let mut complex_buf = vec![Complex::zero(); n];

    let available = samples.len().min(n);
    let offset = samples.len().saturating_sub(available);

    // Apply Hanning Window to minimize spectral leakage
    for i in 0..available {
        let sample_f = samples[offset + i] as f32 / 32768.0;
        let window = 0.5 * (1.0 - (2.0 * PI * i as f32 / (n as f32 - 1.0)).cos());
        complex_buf[i].re = sample_f * window;
    }

    fft_radix2_in_place(&mut complex_buf);

    let half_n = n / 2;
    let mut magnitudes = Vec::with_capacity(half_n);
    let mut max_mag = 0.0f32;
    let mut max_idx = 0usize;

    for i in 0..half_n {
        let mag = (complex_buf[i].magnitude() * 2.0) / (n as f32);
        magnitudes.push(mag);
        if i > 0 && mag > max_mag {
            max_mag = mag;
            max_idx = i;
        }
    }

    let dominant_freq = (max_idx as f32 * sample_rate as f32) / (n as f32);
    (magnitudes, dominant_freq)
}

/// Computes $N$ visual equalizer bands normalized to [0.0..1.0] from PCM samples.
/// Useful for 8-band or 16-band Jetpack Compose audio equalizers.
pub fn compute_equalizer_bands(
    samples: &[i16],
    sample_rate: u32,
    num_bands: usize,
) -> Vec<f32> {
    let bands_count = num_bands.clamp(4, 32);
    if samples.is_empty() {
        return vec![0.0; bands_count];
    }

    let (spectrum, _) = compute_fft_spectrum(samples, sample_rate, 256);
    if spectrum.is_empty() {
        return vec![0.0; bands_count];
    }

    let bin_count = spectrum.len();
    let mut bands = Vec::with_capacity(bands_count);

    // Group frequency bins logarithmically into bands
    for b in 0..bands_count {
        let low_ratio = (b as f32 / bands_count as f32).powi(2);
        let high_ratio = ((b + 1) as f32 / bands_count as f32).powi(2);

        let start_bin = (low_ratio * bin_count as f32).floor() as usize;
        let end_bin = ((high_ratio * bin_count as f32).ceil() as usize)
            .max(start_bin + 1)
            .min(bin_count);

        let mut sum = 0.0f32;
        let count = (end_bin - start_bin).max(1);
        for bin in start_bin..end_bin {
            sum += spectrum[bin];
        }

        let avg = sum / (count as f32);
        // Normalize and boost for responsive visual rendering [0.0, 1.0]
        let band_val = (avg * 8.0).clamp(0.0, 1.0);
        bands.push((band_val * 1000.0).round() / 1000.0);
    }

    bands
}

// ---------------------------------------------------------------------------
// 3. Complete Real-Time Audio Telemetry
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct AudioTelemetry {
    pub normalized_amplitude: f32, // 0.08 .. 1.0 (for Compose live waveform)
    pub peak_amplitude: f32,       // 0.0 .. 1.0
    pub rms_db: f32,               // RMS energy in dBFS
    pub snr_db: f32,               // Signal-to-Noise Ratio in dB
    pub is_speech: bool,           // True if human voice is detected
    pub noise_floor: f32,          // Adaptive background noise floor
    pub dominant_frequency_hz: f32, // Strongest pitch frequency
    pub spectrum_bands: Vec<f32>,  // Normalized visual equalizer bands (e.g. 8 bands)
    pub sample_rate: u32,
}

pub fn analyze_pcm_telemetry(
    pcm_bytes: &[u8],
    sample_rate: u32,
    num_bands: usize,
    adaptive_noise: &mut f32,
) -> AudioTelemetry {
    let sample_count = pcm_bytes.len() / 2;
    if sample_count == 0 {
        return AudioTelemetry {
            normalized_amplitude: 0.08,
            peak_amplitude: 0.0,
            rms_db: -96.0,
            snr_db: 0.0,
            is_speech: false,
            noise_floor: *adaptive_noise,
            dominant_frequency_hz: 0.0,
            spectrum_bands: vec![0.0; num_bands.clamp(4, 32)],
            sample_rate,
        };
    }

    let mut samples = Vec::with_capacity(sample_count);
    let mut sum_sq = 0.0f64;
    let mut max_abs = 0i16;

    for chunk in pcm_bytes.chunks_exact(2) {
        let sample = i16::from_le_bytes([chunk[0], chunk[1]]);
        let abs_val = sample.saturating_abs();
        if abs_val > max_abs {
            max_abs = abs_val;
        }
        let s_f64 = sample as f64;
        sum_sq += s_f64 * s_f64;
        samples.push(sample);
    }

    let mean_sq = sum_sq / (sample_count as f64);
    let rms = mean_sq.sqrt() as f32;
    let peak = (max_abs as f32) / 32768.0;

    let normalized = ((rms / 6500.0) * 0.92 + 0.08).clamp(0.08, 1.0);

    // RMS in dBFS (0 dBFS = max possible sine wave at 32767)
    let rms_db = if rms > 0.0 {
        20.0 * (rms / 32767.0).log10().max(-96.0)
    } else {
        -96.0
    };

    // Update adaptive noise floor
    if rms < *adaptive_noise {
        *adaptive_noise = *adaptive_noise * 0.85 + rms * 0.15;
    } else {
        *adaptive_noise = *adaptive_noise * 0.995 + rms * 0.005;
    }
    *adaptive_noise = (*adaptive_noise).clamp(50.0, 5000.0);

    let snr_db = if *adaptive_noise > 0.0 {
        20.0 * (rms / *adaptive_noise).log10().max(0.0)
    } else {
        0.0
    };

    let is_speech = snr_db > 6.0 && rms > 450.0;

    let (_, dominant_freq) = compute_fft_spectrum(&samples, sample_rate, 256);
    let spectrum_bands = compute_equalizer_bands(&samples, sample_rate, num_bands);

    AudioTelemetry {
        normalized_amplitude: (normalized * 1000.0).round() / 1000.0,
        peak_amplitude: (peak * 1000.0).round() / 1000.0,
        rms_db: (rms_db * 10.0).round() / 10.0,
        snr_db: (snr_db * 10.0).round() / 10.0,
        is_speech,
        noise_floor: (adaptive_noise.clone() * 10.0).round() / 10.0,
        dominant_frequency_hz: (dominant_freq * 10.0).round() / 10.0,
        spectrum_bands,
        sample_rate,
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ring_buffer_write_read() {
        let mut rb = AudioRingBuffer::new(10);
        let samples = vec![1, 2, 3, 4, 5];
        assert_eq!(rb.write(&samples), 5);
        assert_eq!(rb.read_latest(5), vec![1, 2, 3, 4, 5]);

        // Wrap around
        let more_samples = vec![6, 7, 8, 9, 10, 11, 12];
        rb.write(&more_samples);
        // Latest 5 should be [8, 9, 10, 11, 12]
        assert_eq!(rb.read_latest(5), vec![8, 9, 10, 11, 12]);
        assert_eq!(rb.total_written(), 12);
    }

    #[test]
    fn test_fft_sine_wave_frequency_detection() {
        let sample_rate = 16000;
        let target_freq = 1000.0f32; // 1kHz sine wave
        let n = 256;

        let mut samples = Vec::with_capacity(n);
        for i in 0..n {
            let t = i as f32 / sample_rate as f32;
            let val = (2.0 * PI * target_freq * t).sin();
            samples.push((val * 30000.0) as i16);
        }

        let (mags, dominant_freq) = compute_fft_spectrum(&samples, sample_rate, n);
        assert_eq!(mags.len(), n / 2);
        // Dominant frequency should be close to 1000 Hz (within frequency resolution bin width: 16000/256 = 62.5 Hz)
        assert!(
            (dominant_freq - target_freq).abs() <= 62.5,
            "Expected ~1000Hz, got: {}",
            dominant_freq
        );
    }

    #[test]
    fn test_equalizer_bands_output() {
        let samples = vec![1000i16; 512];
        let bands = compute_equalizer_bands(&samples, 16000, 8);
        assert_eq!(bands.len(), 8);
        for &b in &bands {
            assert!(b >= 0.0 && b <= 1.0);
        }
    }

    #[test]
    fn test_pcm_telemetry_analysis() {
        let mut noise = 300.0f32;
        // Generate 100ms of loud 440Hz sine wave (1600 samples at 16kHz)
        let mut pcm_bytes = Vec::with_capacity(3200);
        for i in 0..1600 {
            let t = i as f32 / 16000.0;
            let val = (2.0 * PI * 440.0 * t).sin();
            let sample = (val * 20000.0) as i16;
            pcm_bytes.extend_from_slice(&sample.to_le_bytes());
        }

        let tel = analyze_pcm_telemetry(&pcm_bytes, 16000, 8, &mut noise);
        assert!(tel.is_speech);
        assert!(tel.normalized_amplitude > 0.5);
        assert!(tel.snr_db > 10.0);
        assert_eq!(tel.spectrum_bands.len(), 8);
        assert!((tel.dominant_frequency_hz - 440.0).abs() <= 65.0);
    }
}
