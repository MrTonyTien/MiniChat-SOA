"""
benchmark_client.py — Benchmark Mini Chat Server
================================================
Mục đích:
  1. Đo hiệu năng server với nhiều client đồng thời
  2. Chứng minh server Java hoạt động với client Python
     → Đây là bằng chứng của nguyên lý SOA Language-agnostic

Cách chạy:
  1. Đảm bảo ChatServer.java đang chạy
  2. Mở Terminal/Command Prompt
  3. Chạy: python benchmark_client.py

Yêu cầu:
  Python 3.7+ (không cần cài thêm thư viện nào)
"""

import socket
import threading
import time
import statistics
import sys
from typing import List, Dict, Optional

# ==================== CẤU HÌNH ====================
HOST            = 'localhost'
PORT            = 6789
WARMUP_MSGS     = 20    # Số tin nhắn warmup (JVM optimize)
MEASURE_MSGS    = 50    # Số tin nhắn đo thực sự
GROUP_NAME      = 'BenchmarkRoom'
TIMEOUT_SEC     = 15    # Timeout cho mỗi kết nối
# ===================================================


def run_single_client(
        client_id: int,
        results: Dict,
        barrier: threading.Barrier,
        num_measure: int = MEASURE_MSGS) -> None:
    """
    Mô phỏng 1 client hoàn chỉnh:
    Login → Join/Create Group → Warmup → Đo → Exit

    Args:
        client_id: ID của client này
        results: Dict dùng chung để lưu kết quả
        barrier: Đồng bộ tất cả client bắt đầu đo cùng lúc
        num_measure: Số tin nhắn cần đo
    """
    latencies = []

    try:
        # Kết nối TCP
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(TIMEOUT_SEC)
        s.connect((HOST, PORT))

        reader = s.makefile('r', encoding='utf-8')

        def send(msg: str):
            """Gửi lệnh đến server"""
            s.sendall((msg + '\n').encode('utf-8'))

        def recv() -> str:
            """Nhận 1 dòng từ server"""
            line = reader.readline()
            return line.strip() if line else ''

        # ---- BƯỚC 1: Đọc tin chào ----
        recv()  # "Chào mừng đến Mini Chat v2.0!"
        recv()  # "Vui lòng đăng nhập..."

        # ---- BƯỚC 2: Đăng nhập ----
        username = f"PyClient_{client_id:03d}"
        send(f"LOGIN:{username}")

        response = recv()
        if 'ERROR' in response:
            results[client_id] = {
                'error': f"Login failed: {response}",
                'latencies': []
            }
            return

        # Đọc thêm dòng "Số người online"
        recv()

        # ---- BƯỚC 3: Tham gia nhóm benchmark ----
        send(f"JOIN_GROUP:{GROUP_NAME}")
        response = recv()

        if 'ERROR' in response and 'không tồn tại' in response:
            # Nhóm chưa có, tạo mới
            send(f"CREATE_GROUP:{GROUP_NAME}")
            recv()
            # Join lại
            send(f"JOIN_GROUP:{GROUP_NAME}")
            recv()

        # ---- BƯỚC 4: WARMUP (không đo) ----
        for i in range(WARMUP_MSGS):
            send(f"SEND_MSG:{GROUP_NAME}|warmup_{i}")
            recv()

        # ---- BƯỚC 5: Chờ tất cả client sẵn sàng ----
        # barrier.wait() sẽ chặn cho đến khi
        # TẤT CẢ client đều gọi wait()
        # Sau đó tất cả được giải phóng cùng lúc
        barrier.wait(timeout=30)

        # ---- BƯỚC 6: ĐO LATENCY THỰC SỰ ----
        for i in range(num_measure):
            msg = (f"SEND_MSG:{GROUP_NAME}|"
                   f"bench_c{client_id}_m{i}")

            t_start = time.perf_counter()  # Microsecond precision
            send(msg)
            recv()  # Đợi server echo lại
            t_end = time.perf_counter()

            latency_ms = (t_end - t_start) * 1000
            latencies.append(latency_ms)

        # ---- BƯỚC 7: Thoát ----
        send("EXIT")
        s.close()

    except threading.BrokenBarrierError:
        results[client_id] = {
            'error': 'Barrier timeout — quá nhiều client chậm',
            'latencies': latencies
        }
        return
    except socket.timeout:
        results[client_id] = {
            'error': f'Connection timeout after {TIMEOUT_SEC}s',
            'latencies': latencies
        }
        return
    except Exception as e:
        results[client_id] = {
            'error': str(e),
            'latencies': latencies
        }
        return

    # Lưu kết quả thành công
    results[client_id] = {
        'error': None,
        'latencies': latencies
    }


def percentile(data: List[float], p: float) -> float:
    """Tính percentile thứ p của data đã sắp xếp"""
    if not data:
        return 0.0
    idx = int(len(data) * p / 100)
    idx = max(0, min(idx, len(data) - 1))
    return data[idx]


def run_benchmark(num_clients: int) -> Optional[Dict]:
    """
    Chạy benchmark với num_clients client đồng thời

    Returns:
        Dict chứa kết quả, hoặc None nếu thất bại
    """
    print(f"\n{'─'*60}")
    print(f"  BENCHMARK | {num_clients} clients đồng thời")
    print(f"  Warmup: {WARMUP_MSGS} msgs | Measure: {MEASURE_MSGS} msgs")
    print(f"{'─'*60}")

    results = {}

    # Barrier đồng bộ: đợi đủ num_clients + 1 (thread chính)
    # trước khi bắt đầu đo
    barrier = threading.Barrier(num_clients)

    # Tạo và khởi động tất cả thread
    threads = []
    for i in range(num_clients):
        t = threading.Thread(
            target=run_single_client,
            args=(i, results, barrier, MEASURE_MSGS),
            name=f"client-{i:03d}"
        )
        threads.append(t)

    # Ghi thời gian tổng
    t_total_start = time.perf_counter()

    # Khởi động tất cả
    for t in threads:
        t.start()

    # Chờ tất cả hoàn thành
    for t in threads:
        t.join(timeout=120)

    elapsed_total = time.perf_counter() - t_total_start

    # Tổng hợp kết quả
    all_latencies = []
    error_count = 0

    for cid, result in results.items():
        if result.get('error'):
            error_count += 1
            print(f"  ⚠️  Client {cid:03d} error: {result['error']}")
        else:
            all_latencies.extend(result['latencies'])

    if not all_latencies:
        print(f"  ❌ Không có dữ liệu — {error_count} client lỗi")
        return None

    # Sắp xếp để tính percentile
    all_latencies.sort()
    n = len(all_latencies)

    # Tính các metrics
    avg    = statistics.mean(all_latencies)
    median = statistics.median(all_latencies)
    stdev  = statistics.stdev(all_latencies) if n > 1 else 0
    p50    = percentile(all_latencies, 50)
    p75    = percentile(all_latencies, 75)
    p95    = percentile(all_latencies, 95)
    p99    = percentile(all_latencies, 99)
    p999   = percentile(all_latencies, 99.9)
    max_l  = all_latencies[-1]
    min_l  = all_latencies[0]
    throughput = n / elapsed_total

    # In kết quả chi tiết
    print(f"\n  Tổng tin nhắn đo: {n:,}")
    print(f"  Lỗi: {error_count}/{num_clients} client")
    print(f"  Tổng thời gian: {elapsed_total:.2f}s")
    print(f"  Throughput: {throughput:,.0f} msg/s")
    print()
    print(f"  {'Metric':<12} {'Latency':>12}")
    print(f"  {'─'*26}")
    print(f"  {'Min':<12} {min_l:>10.2f} ms")
    print(f"  {'Average':<12} {avg:>10.2f} ms")
    print(f"  {'Median':<12} {median:>10.2f} ms")
    print(f"  {'StdDev':<12} {stdev:>10.2f} ms")
    print(f"  {'p50':<12} {p50:>10.2f} ms")
    print(f"  {'p75':<12} {p75:>10.2f} ms")
    print(f"  {'p95':<12} {p95:>10.2f} ms")
    print(f"  {'p99':<12} {p99:>10.2f} ms")
    print(f"  {'p99.9':<12} {p999:>10.2f} ms")
    print(f"  {'Max':<12} {max_l:>10.2f} ms")

    # Đánh giá theo SLA
    if p99 < 50:
        rating = "🟢 XUẤT SẮC (p99 < 50ms)"
    elif p99 < 150:
        rating = "🟡 ĐẠT YÊU CẦU (p99 < 150ms)"
    else:
        rating = "🔴 CẦN CẢI THIỆN (p99 ≥ 150ms)"

    print(f"\n  Đánh giá: {rating}")

    return {
        'num_clients':  num_clients,
        'total_msgs':   n,
        'elapsed':      elapsed_total,
        'throughput':   throughput,
        'avg':          avg,
        'stdev':        stdev,
        'p50':          p50,
        'p95':          p95,
        'p99':          p99,
        'max':          max_l,
        'errors':       error_count
    }


def check_server_running() -> bool:
    """Kiểm tra server có đang chạy không"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(3)
        s.connect((HOST, PORT))
        s.close()
        return True
    except:
        return False


def main():
    print("=" * 60)
    print("  MINI CHAT v2.0 — PERFORMANCE BENCHMARK")
    print("  Python Client → Java Server")
    print("  Chứng minh nguyên lý SOA: Language-agnostic")
    print("=" * 60)

    # Kiểm tra server
    print(f"\n⏳ Kiểm tra server tại {HOST}:{PORT}...")
    if not check_server_running():
        print(f"❌ Server không phản hồi tại {HOST}:{PORT}")
        print("   → Hãy khởi động ChatServer.java trước!")
        print("   → Trong Eclipse: Chuột phải ChatServer.java")
        print("     → Run As → Java Application")
        sys.exit(1)

    print(f"✅ Server đang chạy tại {HOST}:{PORT}\n")

    # Chạy benchmark với số client tăng dần
    test_cases = [2, 5, 10, 20]
    all_results = []

    for num_clients in test_cases:
        result = run_benchmark(num_clients)
        if result:
            all_results.append(result)
        # Nghỉ 3 giây giữa các lần test
        # Cho phép server xử lý hết kết nối cũ
        print("\n  ⏳ Chờ 3 giây...")
        time.sleep(3)

    # In bảng tổng hợp
    if all_results:
        print(f"\n\n{'='*75}")
        print("  BẢNG TỔNG HỢP KẾT QUẢ BENCHMARK")
        print(f"{'='*75}")
        header = (f"  {'Clients':<10} {'Msgs':<8} "
                  f"{'Throughput':<15} {'Avg(ms)':<10} "
                  f"{'p50(ms)':<10} {'p95(ms)':<10} "
                  f"{'p99(ms)':<10} {'Errors':<8}")
        print(header)
        print(f"  {'─'*70}")

        for r in all_results:
            row = (f"  {r['num_clients']:<10} "
                   f"{r['total_msgs']:<8} "
                   f"{r['throughput']:<15.0f} "
                   f"{r['avg']:<10.1f} "
                   f"{r['p50']:<10.1f} "
                   f"{r['p95']:<10.1f} "
                   f"{r['p99']:<10.1f} "
                   f"{r['errors']:<8}")
            print(row)

        print(f"\n  Ghi chú: Throughput = tổng tin/giây toàn bộ clients")
        print(f"           p99 = 99% tin nhắn có latency dưới giá trị này")

    print(f"\n{'='*60}")
    print("✅ Benchmark hoàn tất!")
    print()
    print("📋 Hướng dẫn đưa kết quả vào báo cáo:")
    print("   1. Chụp ảnh màn hình terminal này")
    print("   2. Đưa vào Chương 4.3 thay thế bảng cũ")
    print("   3. Ghi chú: 'Benchmark thực hiện bằng Python client'")
    print("   4. Đây là bằng chứng SOA language-agnostic")
    print("      (Python client giao tiếp với Java server)")


if __name__ == '__main__':
    main()