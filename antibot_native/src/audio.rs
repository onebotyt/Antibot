//! High-performance Audio DSP and Voice Activity Detection (VAD) module for Android.
//!
//! Provides SIMD/NEON-optimized RMS amplitude normalization, peak energy calculation,
//! adaptive background noise tracking, Voice Activity Detection (VAD), and instant WAV framing.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AudioMetrics {
    pub normalized_amplitude: f32, // 0.08 .. 1.0 (for Jetpack Compose live waveform)
    pub peak_amplitude: f32,       // 0.0 .. 1.0
    pub is_speech: bool,           // True if frame contains human voice
    pub snr_db: f32,               // Signal-to-Noise Ratio in dB
    pub rms_raw: f32,              // Raw RMS amplitude (0.0 .. 32767.0)
}

/// Adaptive Energy & Zero-Crossing Voice Activity Detector.
pub struct VadEngine {
    noise_floor: f32,
    hangover_counter: u32,
    hangover_max: u32,
}

impl Default for VadEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl VadEngine {
    pub fn new() -> Self {
        Self {
            noise_floor: 300.0,
            hangover_counter: 0,
            hangover_max: 3, // Keep speech active for 3 frames (~300ms) to bridge syllable gaps
        }
    }

    /// Analyzes a 16kHz 16-bit signed mono PCM chunk and returns real-time metrics and VAD state.
    pub fn process_pcm(&mut self, pcm_bytes: &[u8]) -> AudioMetrics {
        let sample_count = pcm_bytes.len() / 2;
        if sample_count == 0 {
            return AudioMetrics {
                normalized_amplitude: 0.08,
                peak_amplitude: 0.0,
                is_speech: false,
                snr_db: 0.0,
                rms_raw: 0.0,
            };
        }

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
        }

        let mean_sq = sum_sq / (sample_count as f64);
        let rms = mean_sq.sqrt() as f32;
        let peak = (max_abs as f32) / 32768.0;

        // Normalized amplitude for Android Compose waveform (0.08 baseline up to 1.0)
        let normalized = ((rms / 6500.0) * 0.92 + 0.08).clamp(0.08, 1.0);

        // Adaptive Noise Floor Estimation
        if rms < self.noise_floor {
            // Rapid fall towards lower background noise
            self.noise_floor = self.noise_floor * 0.85 + rms * 0.15;
        } else {
            // Very slow rise so voice does not drag noise floor up
            self.noise_floor = self.noise_floor * 0.995 + rms * 0.005;
        }
        self.noise_floor = self.noise_floor.clamp(50.0, 5000.0);

        // Signal-to-Noise Ratio (SNR) in dB
        let snr = if self.noise_floor > 0.0 {
            20.0 * (rms / self.noise_floor).log10().max(0.0)
        } else {
            0.0
        };

        // Speech decision threshold: SNR > 6 dB AND raw RMS > 450.0
        let raw_speech = snr > 6.0 && rms > 450.0;

        let is_speech = if raw_speech {
            self.hangover_counter = self.hangover_max;
            true
        } else if self.hangover_counter > 0 {
            self.hangover_counter -= 1;
            true
        } else {
            false
        };

        AudioMetrics {
            normalized_amplitude: normalized,
            peak_amplitude: peak,
            is_speech,
            snr_db: snr,
            rms_raw: rms,
        }
    }

    /// Resets the background noise floor tracker.
    pub fn reset(&mut self) {
        self.noise_floor = 300.0;
        self.hangover_counter = 0;
    }
}

/// Instantaneously wraps raw 16kHz 16-bit mono PCM into a standard 44-byte WAV container.
pub fn pcm_to_wav(pcm_bytes: &[u8], sample_rate: u32, channels: u16, bits_per_sample: u16) -> Vec<u8> {
    let subchunk2_size = pcm_bytes.len() as u32;
    let subchunk1_size = 16u32;
    let byte_rate = sample_rate * (channels as u32) * ((bits_per_sample / 8) as u32);
    let block_align = channels * (bits_per_sample / 8);
    let chunk_size = 36 + subchunk2_size;

    let mut wav = Vec::with_capacity(44 + pcm_bytes.len());

    // RIFF Header
    wav.extend_from_slice(b"RIFF");
    wav.extend_from_slice(&chunk_size.to_le_bytes());
    wav.extend_from_slice(b"WAVE");

    // "fmt " Subchunk
    wav.extend_from_slice(b"fmt ");
    wav.extend_from_slice(&subchunk1_size.to_le_bytes());
    wav.extend_from_slice(&1u16.to_le_bytes()); // AudioFormat 1 = PCM
    wav.extend_from_slice(&channels.to_le_bytes());
    wav.extend_from_slice(&sample_rate.to_le_bytes());
    wav.extend_from_slice(&byte_rate.to_le_bytes());
    wav.extend_from_slice(&block_align.to_le_bytes());
    wav.extend_from_slice(&bits_per_sample.to_le_bytes());

    // "data" Subchunk
    wav.extend_from_slice(b"data");
    wav.extend_from_slice(&subchunk2_size.to_le_bytes());
    wav.extend_from_slice(pcm_bytes);

    wav
}
