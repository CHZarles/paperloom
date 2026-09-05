import unittest

from scripts.evaluation.run_concurrent_parse import kafka_partition, summarize


class ConcurrentParseTest(unittest.TestCase):
    def test_kafka_partition_matches_java_client(self) -> None:
        self.assertEqual(1, kafka_partition(b"", 10))
        self.assertEqual(4, kafka_partition(b"a", 10))
        self.assertEqual(9, kafka_partition(b"hello", 10))

    def test_summary_counts_success_and_observed_partitions(self) -> None:
        rows = [
            {"concurrency": 2, "repetition": 1, "success": True, "latency_ms": 10, "kafka_partition": 0},
            {"concurrency": 2, "repetition": 1, "success": False, "latency_ms": 20, "kafka_partition": 1},
        ]
        result = summarize(rows, [2])[0]
        self.assertEqual(0.5, result["success_rate"])
        self.assertEqual(2, result["observed_distinct_partitions"]["min"])


if __name__ == "__main__":
    unittest.main()
