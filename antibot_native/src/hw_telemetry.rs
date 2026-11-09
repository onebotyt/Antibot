//! Native Hardware Telemetry, CPU Topology, and Thermal Throttling Governor in Rust.
//!
//! Provides:
//! - Direct `/sys/devices/system/cpu` parsing for core online status and frequencies (kHz).
//! - Direct `/proc/meminfo` zero-allocation parsing for RAM metrics (total, free, available, cached, used %).
//! - Multi-zone thermal sensor inspection (`/sys/class/thermal`, `/sys/class/power_supply`).
//! - Thermal mitigation governor computing throttling multipliers (1.0 to 0.1), token pacing delays,
//!   and recommended worker thread counts to protect mobile devices from thermal runaway.

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::Path;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ThermalSeverity {
    Normal,
    Warm,
    Hot,
    Severe,
    Critical,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ThrottleAdvice {
    pub severity: ThermalSeverity,
    pub temperature_celsius: f32,
    pub throttle_multiplier: f32,
    pub recommended_threads: usize,
    pub pause_generation_ms: u64,
    pub message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct CpuCoreInfo {
    pub core_id: usize,
    pub cur_freq_khz: u64,
    pub min_freq_khz: u64,
    pub max_freq_khz: u64,
    pub online: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct MemoryMetrics {
    pub total_mb: u64,
    pub available_mb: u64,
    pub free_mb: u64,
    pub cached_mb: u64,
    pub used_mb: u64,
    pub used_percentage: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ThermalZoneInfo {
    pub zone_id: usize,
    pub zone_type: String,
    pub temp_celsius: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct HardwareTelemetry {
    pub cpu_cores: Vec<CpuCoreInfo>,
    pub online_cores_count: usize,
    pub avg_cpu_freq_khz: u64,
    pub max_cpu_freq_khz: u64,
    pub memory: MemoryMetrics,
    pub thermals: Vec<ThermalZoneInfo>,
    pub max_temperature_celsius: f32,
    pub throttle_advice: ThrottleAdvice,
}

/// Parses CPU ranges like "0-7" or "0,2-4,7" into a list of core IDs.
pub fn parse_online_cores(s: &str) -> Vec<usize> {
    let mut cores = Vec::new();
    for part in s.trim().split(',') {
        let part = part.trim();
        if part.is_empty() {
            continue;
        }
        if let Some((start_str, end_str)) = part.split_once('-') {
            if let (Ok(start), Ok(end)) = (start_str.parse::<usize>(), end_str.parse::<usize>()) {
                for c in start..=end {
                    cores.push(c);
                }
            }
        } else if let Ok(c) = part.parse::<usize>() {
            cores.push(c);
        }
    }
    cores.sort_unstable();
    cores.dedup();
    cores
}

/// Parses `/proc/meminfo` contents into structured `MemoryMetrics`.
pub fn parse_meminfo(content: &str) -> MemoryMetrics {
    let mut total_kb = 0u64;
    let mut free_kb = 0u64;
    let mut available_kb = 0u64;
    let mut cached_kb = 0u64;

    for line in content.lines() {
        let mut parts = line.split_whitespace();
        let key = parts.next().unwrap_or_default();
        let val = parts
            .next()
            .and_then(|v| v.parse::<u64>().ok())
            .unwrap_or_default();

        match key {
            "MemTotal:" => total_kb = val,
            "MemFree:" => free_kb = val,
            "MemAvailable:" => available_kb = val,
            "Cached:" => cached_kb = val,
            _ => {}
        }
    }

    if available_kb == 0 && total_kb > 0 {
        // Fallback calculation if MemAvailable is missing on older kernels
        available_kb = free_kb + cached_kb;
    }

    let total_mb = total_kb / 1024;
    let available_mb = available_kb / 1024;
    let free_mb = free_kb / 1024;
    let cached_mb = cached_kb / 1024;
    let used_mb = total_mb.saturating_sub(available_mb);
    let used_percentage = if total_mb > 0 {
        ((used_mb as f32 / total_mb as f32) * 1000.0).round() / 10.0
    } else {
        0.0
    };

    MemoryMetrics {
        total_mb,
        available_mb,
        free_mb,
        cached_mb,
        used_mb,
        used_percentage,
    }
}

/// Evaluates thermal status and computes adaptive throttling advice.
pub fn evaluate_thermal_throttle(temp_celsius: f32, total_cores: usize) -> ThrottleAdvice {
    let cores = total_cores.max(1);

    if temp_celsius < 40.0 {
        ThrottleAdvice {
            severity: ThermalSeverity::Normal,
            temperature_celsius: temp_celsius,
            throttle_multiplier: 1.0,
            recommended_threads: cores,
            pause_generation_ms: 0,
            message: "Operating within safe thermal limits. Peak performance enabled.".to_string(),
        }
    } else if temp_celsius < 48.0 {
        let rec = ((cores * 3) / 4).max(1);
        ThrottleAdvice {
            severity: ThermalSeverity::Warm,
            temperature_celsius: temp_celsius,
            throttle_multiplier: 0.8,
            recommended_threads: rec,
            pause_generation_ms: 5,
            message: "Mild device warming detected. Pacing token generation slightly.".to_string(),
        }
    } else if temp_celsius < 58.0 {
        let rec = (cores / 2).max(1);
        ThrottleAdvice {
            severity: ThermalSeverity::Hot,
            temperature_celsius: temp_celsius,
            throttle_multiplier: 0.6,
            recommended_threads: rec,
            pause_generation_ms: 20,
            message: "Elevated temperature. Active thermal mitigation enabled.".to_string(),
        }
    } else if temp_celsius < 68.0 {
        let rec = (cores / 4).max(1);
        ThrottleAdvice {
            severity: ThermalSeverity::Severe,
            temperature_celsius: temp_celsius,
            throttle_multiplier: 0.35,
            recommended_threads: rec,
            pause_generation_ms: 50,
            message: "Severe thermal throttling! Throttling thread pool to avoid shutdown.".to_string(),
        }
    } else {
        ThrottleAdvice {
            severity: ThermalSeverity::Critical,
            temperature_celsius: temp_celsius,
            throttle_multiplier: 0.1,
            recommended_threads: 1,
            pause_generation_ms: 200,
            message: "CRITICAL THERMAL GUARD ACTIVE: Minimizing computation to prevent emergency shutdown.".to_string(),
        }
    }
}

fn read_u64_file(path: &Path) -> u64 {
    fs::read_to_string(path)
        .ok()
        .and_then(|s| s.trim().parse::<u64>().ok())
        .unwrap_or(0)
}

/// Collects hardware telemetry (CPU, RAM, thermals, throttle advice).
/// `override_temp` can be provided by the Android battery manager / app sensor if `/sys` is unreadable.
pub fn collect_hardware_telemetry(override_temp: Option<f32>) -> HardwareTelemetry {
    // 1. Read Online Cores
    let online_str = fs::read_to_string("/sys/devices/system/cpu/online").unwrap_or_default();
    let mut online_set = parse_online_cores(&online_str);
    if online_set.is_empty() {
        let detected = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(4);
        online_set = (0..detected).collect();
    }

    // 2. Read CPU frequencies per core
    let mut cpu_cores = Vec::new();
    let mut total_freq = 0u64;
    let mut max_freq = 0u64;
    let mut online_count = 0usize;

    for &core_id in &online_set {
        let cpufreq_dir = format!("/sys/devices/system/cpu/cpu{}/cpufreq", core_id);
        let path = Path::new(&cpufreq_dir);

        let cur_freq = read_u64_file(&path.join("scaling_cur_freq"));
        let min_freq = read_u64_file(&path.join("cpuinfo_min_freq"));
        let max_freq_val = read_u64_file(&path.join("cpuinfo_max_freq"));

        if cur_freq > 0 {
            total_freq += cur_freq;
            if cur_freq > max_freq {
                max_freq = cur_freq;
            }
        }
        online_count += 1;

        cpu_cores.push(CpuCoreInfo {
            core_id,
            cur_freq_khz: cur_freq,
            min_freq_khz: min_freq,
            max_freq_khz: max_freq_val,
            online: true,
        });
    }

    let avg_freq = if online_count > 0 && total_freq > 0 {
        total_freq / online_count as u64
    } else {
        max_freq
    };

    // 3. Read Memory
    let meminfo_str = fs::read_to_string("/proc/meminfo").unwrap_or_default();
    let memory = parse_meminfo(&meminfo_str);

    // 4. Read Thermals
    let mut thermals = Vec::new();
    let mut max_temp = 0.0f32;

    // Try thermal zones
    for zone_id in 0..16 {
        let temp_path = format!("/sys/class/thermal/thermal_zone{}/temp", zone_id);
        let type_path = format!("/sys/class/thermal/thermal_zone{}/type", zone_id);

        if let Ok(raw_temp_str) = fs::read_to_string(&temp_path) {
            let raw_temp: f32 = raw_temp_str.trim().parse().unwrap_or(0.0);
            let temp_c = if raw_temp > 1000.0 {
                raw_temp / 1000.0
            } else {
                raw_temp
            };

            let zone_type = fs::read_to_string(&type_path)
                .map(|s| s.trim().to_string())
                .unwrap_or_else(|_| format!("zone_{}", zone_id));

            if temp_c > max_temp {
                max_temp = temp_c;
            }

            thermals.push(ThermalZoneInfo {
                zone_id,
                zone_type,
                temp_celsius: (temp_c * 10.0).round() / 10.0,
            });
        }
    }

    // Try battery temperature
    if thermals.is_empty() {
        let batt_path = "/sys/class/power_supply/battery/temp";
        if let Ok(raw_str) = fs::read_to_string(batt_path) {
            let raw: f32 = raw_str.trim().parse().unwrap_or(0.0);
            let temp_c = if raw > 100.0 { raw / 10.0 } else { raw };
            if temp_c > max_temp {
                max_temp = temp_c;
            }
            thermals.push(ThermalZoneInfo {
                zone_id: 0,
                zone_type: "battery".to_string(),
                temp_celsius: (temp_c * 10.0).round() / 10.0,
            });
        }
    }

    // If override provided from BatteryManager or client
    if let Some(override_val) = override_temp {
        if override_val > 0.0 {
            max_temp = override_val;
            thermals.insert(
                0,
                ThermalZoneInfo {
                    zone_id: 99,
                    zone_type: "override_sensor".to_string(),
                    temp_celsius: (override_val * 10.0).round() / 10.0,
                },
            );
        }
    }

    // Default safe ambient temperature if no sensor accessible
    if max_temp <= 0.0 {
        max_temp = 35.0;
    }

    let throttle_advice = evaluate_thermal_throttle(max_temp, online_count);

    HardwareTelemetry {
        cpu_cores,
        online_cores_count: online_count,
        avg_cpu_freq_khz: avg_freq,
        max_cpu_freq_khz: max_freq,
        memory,
        thermals,
        max_temperature_celsius: (max_temp * 10.0).round() / 10.0,
        throttle_advice,
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_online_cores() {
        let cores = parse_online_cores("0-3,6-7");
        assert_eq!(cores, vec![0, 1, 2, 3, 6, 7]);

        let single = parse_online_cores("0-7");
        assert_eq!(single, vec![0, 1, 2, 3, 4, 5, 6, 7]);

        let individual = parse_online_cores("0, 2, 4");
        assert_eq!(individual, vec![0, 2, 4]);
    }

    #[test]
    fn test_parse_meminfo() {
        let mock = "\
MemTotal:        6000000 kB
MemFree:          500000 kB
MemAvailable:    2500000 kB
Buffers:            1000 kB
Cached:          1000000 kB
";
        let mem = parse_meminfo(mock);
        assert_eq!(mem.total_mb, 6000000 / 1024);
        assert_eq!(mem.available_mb, 2500000 / 1024);
        assert_eq!(mem.free_mb, 500000 / 1024);
        assert_eq!(mem.cached_mb, 1000000 / 1024);
        assert!(mem.used_percentage > 50.0 && mem.used_percentage < 65.0);
    }

    #[test]
    fn test_thermal_throttle_levels() {
        let normal = evaluate_thermal_throttle(36.5, 8);
        assert_eq!(normal.severity, ThermalSeverity::Normal);
        assert_eq!(normal.recommended_threads, 8);
        assert_eq!(normal.throttle_multiplier, 1.0);
        assert_eq!(normal.pause_generation_ms, 0);

        let warm = evaluate_thermal_throttle(44.0, 8);
        assert_eq!(warm.severity, ThermalSeverity::Warm);
        assert_eq!(warm.recommended_threads, 6);
        assert_eq!(warm.throttle_multiplier, 0.8);

        let hot = evaluate_thermal_throttle(52.0, 8);
        assert_eq!(hot.severity, ThermalSeverity::Hot);
        assert_eq!(hot.recommended_threads, 4);
        assert_eq!(hot.throttle_multiplier, 0.6);

        let severe = evaluate_thermal_throttle(62.0, 8);
        assert_eq!(severe.severity, ThermalSeverity::Severe);
        assert_eq!(severe.recommended_threads, 2);
        assert_eq!(severe.throttle_multiplier, 0.35);

        let critical = evaluate_thermal_throttle(72.0, 8);
        assert_eq!(critical.severity, ThermalSeverity::Critical);
        assert_eq!(critical.recommended_threads, 1);
        assert_eq!(critical.throttle_multiplier, 0.1);
        assert_eq!(critical.pause_generation_ms, 200);
    }

    #[test]
    fn test_collect_hardware_telemetry_safe() {
        let telem = collect_hardware_telemetry(Some(42.5));
        assert!(telem.online_cores_count >= 1);
        assert_eq!(telem.max_temperature_celsius, 42.5);
        assert_eq!(telem.throttle_advice.severity, ThermalSeverity::Warm);
    }
}
