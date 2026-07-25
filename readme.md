Nguyên tắc cốt lõi: đừng để Claude tự nhớ, hãy cho nó đọc

Mỗi session Claude Code bắt đầu với context rỗng. Nếu bạn giải thích lại thiết kế bằng lời mỗi lần → tốn token và dễ bị Claude hiểu lệch. Thay vào đó, dùng file.

1. Setup CLAUDE.md trong repo (quan trọng nhất)

Tạo file CLAUDE.md ở root repo — Claude Code tự động đọc file này đầu mỗi session, không cần bạn dán lại. Giữ nó ngắn, chỉ chứa thứ cần trong MỌI session:

markdown

# Project: Job & File Management Platform

## Kiến trúc

Xem chi tiết đầy đủ: @docs/design/job-file-management.md

## Quy ước bắt buộc

- Modular monolith, package theo domain: file/, job/, export/, import/, shared/
- Không import package Kafka/Redis trong business code — chỉ dùng JobEventPublisher/Consumer interface
- Mọi job phải qua AbstractJobProcessor (Template Method), không tự viết flow status riêng
- CAS update status bằng WHERE status='...' + check rowsAffected
- Authorization check ở tầng API, không check trong worker

## Build & test

mvn spring-boot:run
mvn test

Copy file design doc tôi vừa tạo vào docs/design/job-file-management.md trong repo, rồi @ reference nó trong CLAUDE.md như trên (Claude Code hỗ trợ import bằng @path, tối đa 5 tầng lồng nhau) — nó chỉ load khi cần, không phình CLAUDE.md.

2. Làm theo từng Phase, dùng /clear giữa các phase

Đừng làm cả 6 phase trong 1 session dài. Mỗi khi chuyển phase (VD: xong Phase 1 - schema DB, chuyển sang Phase 2 - file module):

/clear

Lý do: context cũ (toàn bộ code đã viết, các lần sửa qua lại) không giúp ích gì cho phase mới mà vẫn tính token mỗi lượt. CLAUDE.md + design doc sẽ tự nạp lại context cần thiết, không mất gì quan trọng.

Tránh dùng /compact trừ khi thực sự cần giữ nguyên 1 phiên dài — /compact tóm tắt nhưng vẫn tốn token hơn /clear sạch.

3. Theo dõi chi phí, đừng để bất ngờ
   /cost

Chạy lệnh này định kỳ (30-45 phút làm việc) để biết đã tốn bao nhiêu — nếu context đang phình to (nhiều file lớn được đọc, nhiều lần sửa qua lại), đó là dấu hiệu nên /clear và bắt đầu task tiếp theo sạch sẽ.

4. Ra lệnh cụ thể, đừng để Claude tự đoán lại kiến trúc

Sai (tốn token vì Claude phải suy luận lại, có thể lệch design):

"viết cho tôi job xử lý export"

Đúng:

"Đọc docs/design/job-file-management.md mục 6, implement AbstractJobProcessor và JobProcessorFactory theo đúng pattern đã mô tả, chưa cần viết ExportCsvProcessor cụ thể"

Càng cụ thể, Claude càng ít phải "đoán" — đoán sai thì bạn phải sửa lại, tốn gấp đôi token.

5. Việc nào giao Claude, việc nào bạn tự quyết
   Giao Claude: viết code theo pattern đã chốt, viết test, refactor, giải thích lỗi.
   Bạn tự quyết trước khi giao: những chỗ ảnh hưởng kiến trúc (đã chốt xong trong buổi này rồi, nên phần còn lại chủ yếu là "giao việc", không cần thảo luận lại kiến trúc nữa) — điều này giúp tránh vòng lặp hỏi-đáp dài dòng chỉ để quyết định lại thứ đã quyết.
6. Dùng Plan Mode cho phase phức tạp

Với Phase 3 (Job module — nhiều pattern lồng nhau), nên bật Plan Mode trước khi để Claude code thật: Claude sẽ trình bày kế hoạch triển khai trước, bạn duyệt rồi mới cho chạy — tránh việc Claude viết sai hướng rồi phải viết lại toàn bộ (rất tốn token).

Tóm tắt checklist khi bắt đầu
Copy file design doc vào docs/design/ trong repo
Tạo CLAUDE.md ngắn gọn, reference đến design doc
Bắt đầu Phase 1, dùng Plan Mode nếu phase phức tạp
/cost định kỳ, /clear khi chuyển phase
Ra lệnh cụ thể, trỏ thẳng vào mục trong design doc thay vì diễn giải lại
