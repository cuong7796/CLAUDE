# TFT Boost Pro 3.1 (Shizuku)

App tối ưu tối đa trước khi chơi Đấu Trường Chân Lý Mobile trên Galaxy S25+, chạy lệnh cấp ADB qua Shizuku. Cài song song được với TFT Boost (bản không cần Shizuku).

## Khi bấm "Tối ưu & vào game"

| Tinh chỉnh | Lệnh | Khôi phục |
|---|---|---|
| Khóa 120 FPS – màn hình cố định 120Hz | `settings put system peak_refresh_rate 120.0`, `min_refresh_rate 120.0` | trả giá trị gốc |
| Khóa 120 FPS – Game Mode | `device_config put game_overlay <game> mode=2,fps=120:mode=3,fps=60` | trả giá trị gốc |
| Game Mode hiệu năng | `cmd game mode performance <game>` | `cmd game mode standard` |
| Tắt Tiết kiệm pin | `cmd power set-mode 0` (chỉ khi đang bật) | `cmd power set-mode 1` |
| Wi-Fi độ trễ thấp | `cmd wifi force-low-latency-mode enabled` | `disabled` |
| Tắt quét Wi-Fi/Bluetooth nền | `settings put global wifi/ble_scan_always_enabled 0` | trả giá trị gốc |
| Tạm dừng đồng bộ | `ContentResolver.setMasterSyncAutomatically(false)` | trả giá trị gốc |
| Không làm phiền | `NotificationManager.setInterruptionFilter` | trả giá trị gốc |
| Tắt GOS (mặc định bật) | `pm disable-user --user 0 com.samsung.android.game.gos` | `pm enable` |
| Bỏ giới hạn nhiệt (tùy chọn) | `cmd thermalservice override-status 0` | `cmd thermalservice reset` |
| Buộc dừng app nền | `am force-stop` + `am kill-all` | không cần |

## Tự khôi phục

Dịch vụ `WatchService` đọc nhật ký sử dụng mỗi 2 giây. Khôi phục khi: về màn hình chính > 8 giây, sang app khác > 45 giây, mở lại TFT Boost Pro, tắt màn hình > 10 phút, hoặc bấm "Khôi phục ngay" trên thông báo.
