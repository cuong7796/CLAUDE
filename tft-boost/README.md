# TFT Boost 2.2

App Android tối ưu 1 chạm trước khi chơi Đấu Trường Chân Lý Mobile trên Galaxy S25+. Không cần Shizuku, không cần root.

Khi bấm "Tối ưu & vào game":
1. Bật Không làm phiền.
2. Dọn tiến trình nền bằng API của Android.
3. Dịch vụ Trợ năng tự mở Thông tin ứng dụng của các app đã dùng trong 12 giờ qua và bấm "Buộc dừng" (bỏ qua app trong danh sách giữ lại, bàn phím, tin nhắn, điện thoại, đồng hồ báo thức).
4. Mở danh sách app gần đây và bấm "Đóng tất cả".
5. Mở game.

Khi thoát game (về màn hình chính quá 8 giây, sang app khác quá 45 giây, hoặc mở lại TFT Boost): Không làm phiền trở về như trước khi chơi và Trợ năng tự tắt. Lần tối ưu sau, app mở trang bật Trợ năng; bật xong app tự quay lại chạy tiếp.

Build: GitHub Actions tự chạy khi đẩy mã (`.github/workflows/tft-boost.yml`), APK nằm ở mục Releases.

## Bổ sung ở bản 2.2

- Tạm dừng tự động đồng bộ trong lúc chơi (trả về như cũ khi thoát game).
- Cảnh báo khi Tiết kiệm pin đang bật.
- Lớp phủ nhỏ hiện nhiệt độ, mức nóng hệ thống, pin, RAM khi chơi (tùy chọn).
- Cảnh báo khi máy quá nóng (sắp bị hạ FPS).
- Tự bật chế độ chơi khi mở game trực tiếp (khi Trợ năng đang bật).
- Kiểm tra máy: tần số quét thực tế, Tiết kiệm pin, nhiệt độ, đang sạc, bộ nhớ trong, RAM, Wi-Fi 2.4/5GHz và độ mạnh sóng, quyền của app, kèm hướng dẫn chỉnh Game Booster.
