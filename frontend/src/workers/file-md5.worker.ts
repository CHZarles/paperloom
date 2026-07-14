import SparkMD5 from 'spark-md5';

interface Md5Request {
  file: File;
}

type Md5Response = { type: 'result'; hash: string } | { type: 'error'; message: string };

const workerScope = globalThis as unknown as {
  onmessage: ((event: MessageEvent<Md5Request>) => void) | null;
  postMessage: (message: Md5Response) => void;
};

workerScope.onmessage = async event => {
  try {
    const file = event.data.file;
    const chunkSize = 5 * 1024 * 1024;
    const spark = new SparkMD5.ArrayBuffer();

    for (let start = 0; start < file.size; start += chunkSize) {
      // Hash state is ordered, so chunks must be read and appended sequentially.
      // eslint-disable-next-line no-await-in-loop
      const buffer = await file.slice(start, Math.min(start + chunkSize, file.size)).arrayBuffer();
      spark.append(buffer);
    }

    workerScope.postMessage({ type: 'result', hash: spark.end() });
  } catch (error) {
    workerScope.postMessage({
      type: 'error',
      message: error instanceof Error ? error.message : 'File hashing failed'
    });
  }
};
