# FB Sạch — Facebook không quảng cáo (Android)

Ứng dụng bọc trang web Facebook trong WebView, chặn quảng cáo theo 3 lớp.
File cài đặt: [`../FbSach_v1.0.apk`](../FbSach_v1.0.apk) (Android 7.0 trở lên, ~58 KB).

## Cơ chế chặn

| Lớp | Chặn gì | Nằm ở đâu |
|---|---|---|
| 1. Mạng | Tên miền quảng cáo/theo dõi (doubleclick, googlesyndication, an.facebook.com, pixel `/tr`, `/ajax/bz`…) → trả về rỗng | `MainActivity.BLOCKED_HOSTS`, `isBlockedRequest()` |
| 2. Bảng tin | Bài "Được tài trợ" / "Sponsored" / "Ad" (nhãn đa ngôn ngữ, ký tự icon ẩn, aria-label, `/ads/about`) → gỡ khỏi bố cục, bù vị trí cuộn | `assets/adfilter.js` |
| 3. Cột phải | Khối "Được tài trợ" ở giao diện máy tính | `cleanRightRail()` trong `adfilter.js` |

Tuỳ chọn trong menu: ẩn "Những người bạn có thể biết" (mặc định bật), ẩn bài "Gợi ý cho bạn"/Reels gợi ý (mặc định tắt),
giao diện máy tính, tin nhắn, thông báo, bảng tin mới nhất. Thanh tiêu đề hiển thị tổng số quảng cáo đã chặn.

Liên kết ra ngoài bị Facebook bọc qua `l.facebook.com/l.php?u=…` được bóc địa chỉ thật và mở bằng trình duyệt.

## Mã nguồn tham khảo

- **SlimSocial for Facebook** — https://github.com/rignaneseleo/SlimSocial-for-Facebook (GPL-2.0, còn cập nhật 10/2026).
  Thuật toán nhận diện nhãn tài trợ, bộ chọn `div[data-tracking-duration-id]`, User-Agent Firefox 70 lấy từ đây.
- Frost for Facebook (AllanWang, đã lưu trữ), FaceSlim (indywidualny, đã lưu trữ) — tham khảo hướng tiếp cận WebView.

Vì chuyển thể mã GPL-2.0 nên dự án này cũng theo **GPL-2.0**.

## Build lại

```bash
sudo apt install aapt dalvik-exchange zipalign apksigner openjdk-21-jdk python3-pil
./build.sh          # ra ../FbSach_v<version>.apk
```

Không cần Android Studio/Gradle. `keystore/fbsach.jks` (mật khẩu `fbsach123`) giữ nguyên chữ ký để
cài bản mới đè bản cũ mà không phải gỡ.

## Giới hạn

- Facebook đổi cấu trúc HTML thường xuyên; khi bộ chọn lỗi thời, quảng cáo có thể lọt lại → sửa `adfilter.js`.
- Không chặn được quảng cáo chèn giữa video (in-stream) và nội dung bài đăng do chính trang/người dùng quảng bá
  (không gắn nhãn tài trợ).
- Không có thông báo đẩy như app gốc; tin nhắn dùng bản web.
