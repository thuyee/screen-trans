# Dịch Màn Hình v3.0 — app dịch kiểu Google Dịch + dịch màn hình

Ứng dụng Android viết mới (Kotlin). Bố cục và tập tính năng theo kiểu **Google Dịch**, cộng thêm **dịch màn hình bằng bong bóng nổi**.
**KHÔNG quảng cáo, không analytics, không thanh toán, không SDK theo dõi.** Không dùng logo/tài nguyên của Google.

## Build
**GitHub Actions:** đẩy thư mục này lên nhánh `main` → tab *Actions* → *Build APK* → tải `DichManHinh-release-apk`
(nhẹ, R8) hoặc `DichManHinh-debug-apk` (dự phòng). **Android Studio:** Ladybug+, JDK 17, minSdk 29.

> ⚠️ Mã được viết và kiểm tra tĩnh (XML hợp lệ, id/chuỗi/drawable/lớp/manifest khớp, ngoặc cân bằng) nhưng **chưa biên dịch
> hay chạy thử trên máy thật**. Lần build đầu có thể còn lỗi nhỏ — gửi log lỗi là sửa được.

## Tính năng giống Google Dịch
| Tính năng | Ghi chú |
|---|---|
| Trang chủ dịch văn bản tức thì | gõ / dán / nói; tự nhận diện ngôn ngữ ("Phát hiện: …"); hoán đổi ngôn ngữ |
| Chọn ngôn ngữ | tìm kiếm, "Gần đây", ~130 ngôn ngữ, dấu ✓ cho gói đã tải |
| Kết quả | phiên âm (romanization), nghĩa theo loại từ + định nghĩa cho từ/cụm ngắn, nghe, sao chép, chia sẻ, phóng to |
| Sổ cụm từ | gắn sao ★ để lưu; tìm kiếm; xóa |
| Lịch sử | tự lưu (tắt được), tìm kiếm, gắn sao, nhấn giữ để xóa |
| Nhập giọng nói | dịch vụ nhận dạng của hệ thống |
| Hội thoại hai người | mỗi người một micro, tự đọc bản dịch |
| Phiên âm (Transcribe) | nghe liên tục, chữ + bản dịch song song (cần quyền micro) |
| Viết tay | ML Kit Digital Ink, gợi ý nhiều ứng viên; gói tải theo ngôn ngữ |
| Camera | dịch trực tiếp / đóng băng khung hình; dịch ảnh (chọn/chụp/chia sẻ), cắt, lưu |
| Chép để dịch (Tap to Translate) | sao chép chữ ở app bất kỳ → hiện thẻ dịch nhanh |
| Bôi đen → "Dịch", chia sẻ chữ vào app | có |
| Dịch ngoại tuyến | gói tải theo ngôn ngữ; mặc định chỉ Trung · Anh · Việt |

## Thêm: dịch màn hình (ngoài Google Dịch)
Bong bóng nổi (chạm = cả màn hình, chạm đúp = chọn vùng, nhấn giữ = menu), hai chế độ lấy chữ (chụp + OCR / Trợ năng đọc trực tiếp),
dịch & thay chữ đang gõ, chế độ manga, kiểu chữ/màu/viền tùy biến, camera trực tiếp, giọng đọc tùy chỉnh,
giữ biểu tượng không bị hệ thống tắt, ô Quick Settings. Tất cả miễn phí.

## Chế độ dịch (Cài đặt → Công cụ dịch)
- **Tự động (mặc định, như Google Dịch):** có mạng → Google Dịch web (tốt nhất, nhiều ngôn ngữ nhất, có từ điển/phiên âm);
  mất mạng hoặc lỗi → gói ngoại tuyến ML Kit (nếu cặp ngôn ngữ hỗ trợ).
- **ML Kit:** hoàn toàn trên máy, không gửi gì ra ngoài.
- Google Dịch web / Google Cloud (khóa của bạn) / Gemini / AI tương thích OpenAI.

## Riêng tư — đọc kỹ
- Chế độ Tự động **gửi chữ bạn dịch tới Google** khi có mạng (điểm cuối web công khai, không tài khoản). Muốn không gửi gì: chọn **ML Kit**.
- Google Dịch web là **điểm cuối không chính thức**: có thể bị giới hạn/thay đổi; khi lỗi app quay về ML Kit nếu được.
- Chép để dịch chỉ đọc clipboard khi bạn bật, bỏ qua nội dung nhạy cảm. Trợ năng bỏ qua ô mật khẩu, không ghi thao tác.
- Quyền: hiển thị trên ứng dụng khác, Trợ năng (tùy chọn), camera, micro (chỉ Phiên âm), thông báo, bỏ tối ưu pin (tùy chọn).

## Giảm dung lượng
- Dịch ngoại tuyến: gói ML Kit tải riêng; mặc định gợi ý Trung + Việt (Anh làm trung gian), ngôn ngữ khác tải khi cần.
- OCR: chỉ Latin + Hán đóng gói sẵn; Nhật/Hàn/Devanagari và mô hình viết tay tải khi cần (qua Google Play Services / ML Kit).
- Chỉ ABI arm64-v8a + armeabi-v7a, chỉ tài nguyên vi/en, bản release bật R8 + shrink resources.
- Dung lượng thực chỉ biết sau khi build.

## Khác biệt so với Google Dịch thật (nói thẳng)
- Giao diện *tương tự* (menu trượt, thẻ nhập/kết quả, lối tắt) chứ không sao chép pixel; không có tài khoản/đồng bộ đám mây,
  không có ngôn ngữ ngoại tuyến cho cả ~130 ngôn ngữ (ML Kit chỉ ~59), không có Lens nâng cao (chọn chữ/mua sắm/bài tập).
- Hội thoại dùng hộp thoại nói của hệ thống nên mỗi lượt phải bấm micro (không có chế độ "tự nhận ai đang nói").
- Chép để dịch cần dịch vụ nổi đang chạy và quyền hiển thị trên ứng dụng khác; một số máy chặn mở activity từ nền.
- Nhập giọng nói/hội thoại cần máy có dịch vụ nhận dạng giọng nói (thường là ứng dụng Google).

## Bản quyền
Biểu tượng vector lấy từ Material Icons (Apache-2.0). Thư viện: AndroidX, Material Components, CameraX, Kotlin Coroutines, Google ML Kit / Play Services.
