import { getUploadFileValidationError } from './upload-validation';
import { REQUEST_ID_KEY } from '~/packages/axios/src';
import { nanoid } from '~/packages/utils/src';

const maxConcurrentChunksPerFile = 4;
const maxConcurrentChunkUploads = 4;
let activeChunkUploads = 0;
const chunkUploadWaiters: Array<() => void> = [];

function acquireChunkUploadSlot() {
  return new Promise<() => void>(resolve => {
    const acquire = () => {
      activeChunkUploads += 1;
      let released = false;
      resolve(() => {
        if (released) return;
        released = true;
        activeChunkUploads -= 1;
        chunkUploadWaiters.shift()?.();
      });
    };

    if (activeChunkUploads < maxConcurrentChunkUploads) acquire();
    else chunkUploadWaiters.push(acquire);
  });
}

export const useKnowledgeBaseStore = defineStore(SetupStoreId.KnowledgeBase, () => {
  const tasks = ref<Api.Paper.UploadTask[]>([]);
  const activeUploads = ref<Set<string>>(new Set());

  function mergeUploadedChunks(currentUploadedChunks: number[], latestUploadedChunks: number[]) {
    return Array.from(new Set([...currentUploadedChunks, ...latestUploadedChunks])).sort((a, b) => a - b);
  }

  async function uploadChunk(task: Api.Paper.UploadTask, chunkIndex: number): Promise<boolean> {
    const totalChunks = Math.ceil(task.totalSize / chunkSize);

    const chunkStart = chunkIndex * chunkSize;
    const chunkEnd = Math.min(chunkStart + chunkSize, task.totalSize);
    const chunk = task.file!.slice(chunkStart, chunkEnd);

    const requestId = nanoid();
    task.requestIds ??= [];
    task.requestIds.push(requestId);
    const releaseUploadSlot = await acquireChunkUploadSlot();
    let response;
    try {
      response = await request<Api.Paper.Progress>({
        url: '/papers/upload/chunk',
        method: 'POST',
        data: {
          file: chunk,
          paperId: task.paperId,
          chunkIndex,
          totalSize: task.totalSize,
          paperTitle: task.paperTitle,
          orgTag: task.orgTag,
          isPublic: task.isPublic ?? false
        },
        headers: {
          'Content-Type': 'multipart/form-data',
          [REQUEST_ID_KEY]: requestId
        },
        timeout: 10 * 60 * 1000
      });
    } finally {
      task.requestIds = task.requestIds.filter(id => id !== requestId);
      releaseUploadSlot();
    }

    const { error, data } = response!;

    if (error) return false;

    // 更新任务状态
    const updatedTask = tasks.value.find(t => t.paperId === task.paperId);
    if (!updatedTask) return true;

    updatedTask.chunkIndex = chunkIndex;
    updatedTask.uploadedChunks = mergeUploadedChunks(updatedTask.uploadedChunks, data.uploaded);
    updatedTask.progress = Number.parseFloat(((updatedTask.uploadedChunks.length / totalChunks) * 100).toFixed(2));

    return true;
  }

  async function uploadChunksInParallel(task: Api.Paper.UploadTask, chunkIndexes: number[]) {
    if (chunkIndexes.length === 0) return;

    let uploadError: Error | null = null;
    const workerCount = Math.min(maxConcurrentChunksPerFile, chunkIndexes.length);
    const runWorker = async (): Promise<void> => {
      if (uploadError) return;

      const chunkIndex = chunkIndexes.shift();
      if (chunkIndex === undefined) return;

      const success = await uploadChunk(task, chunkIndex);
      if (!success) {
        uploadError = new Error(`分片 ${chunkIndex} 上传失败`);
        return;
      }

      await runWorker();
    };
    const workers = Array.from({ length: workerCount }, () => runWorker());

    await Promise.all(workers);

    if (uploadError) throw uploadError;
  }

  async function mergeFile(task: Api.Paper.UploadTask) {
    try {
      const { error, data } = await request<Api.Paper.MergeResult>({
        url: '/papers/upload/merge',
        method: 'POST',
        data: { paperId: task.paperId, paperTitle: task.paperTitle }
      });
      if (error) return false;

      // 更新任务状态为已完成
      const index = tasks.value.findIndex(t => t.paperId === task.paperId);
      tasks.value[index].status = UploadStatus.Completed;
      tasks.value[index].progress = 100;
      tasks.value[index].estimatedEmbeddingTokens = data?.estimatedEmbeddingTokens;
      tasks.value[index].estimatedChunkCount = data?.estimatedChunkCount;
      tasks.value[index].processingStatus = 'PROCESSING';
      tasks.value[index].processingErrorMessage = null;
      tasks.value[index].actualEmbeddingTokens = undefined;
      tasks.value[index].actualChunkCount = undefined;

      if (data?.estimatedEmbeddingTokens) {
        const tokenLabel = Number(data.estimatedEmbeddingTokens).toLocaleString();
        const chunkLabel = Number(data.estimatedChunkCount || 0).toLocaleString();
        window.$message?.success(`上传完成，预计向量化消耗 ${tokenLabel} Tokens（${chunkLabel} 个切片）`);
      }
      return true;
    } catch {
      return false;
    }
  }

  /**
   * 异步函数：将上传请求加入队列
   *
   * 本函数处理上传任务的排队和初始化工作它首先检查是否存在相同的文件， 如果不存在，则创建一个新的上传任务，并将其添加到任务队列中最后启动上传流程
   *
   * @param form 包含上传信息的表单，包括文件列表和是否公开的标签
   * @returns 返回一个上传任务对象，无论是已存在的还是新创建的
   */
  async function enqueueUpload(form: Api.Paper.Form) {
    // 获取文件列表中的第一个文件
    const file = form.fileList?.[0]?.file ?? null;
    const validationError = getUploadFileValidationError(file);
    if (validationError || !file) {
      window.$message?.error(validationError);
      return;
    }
    // 计算文件的MD5值，用于唯一标识文件
    const md5 = await calculateMD5(file);

    // 检查是否已存在相同文件
    const existingTask = tasks.value.find(t => t.paperId === md5);
    if (existingTask) {
      // 如果存在相同文件，直接返回该上传任务
      if (existingTask.status === UploadStatus.Completed) {
        window.$message?.error('文件已存在');
        return;
      } else if (existingTask.status === UploadStatus.Pending || existingTask.status === UploadStatus.Uploading) {
        window.$message?.error('文件正在上传中');
        return;
      } else if (existingTask.status === UploadStatus.Break) {
        existingTask.status = UploadStatus.Pending;
        startUpload();
        return;
      }
    }

    // 创建新的上传任务对象
    const newTask: Api.Paper.UploadTask = {
      file,
      chunk: null,
      chunkIndex: 0,
      paperId: md5,
      paperTitle: file.name,
      originalFilename: file.name,
      totalSize: file.size,
      isPublic: form.isPublic,
      uploadedChunks: [],
      progress: 0,
      status: UploadStatus.Pending,
      orgTag: form.orgTag,
      processingStatus: 'PENDING',
      processingErrorMessage: null
    };

    newTask.orgTagName = form.orgTagName ?? null;

    // 将新的上传任务添加到任务队列中
    tasks.value.push(newTask);
    // 启动上传流程
    startUpload();
    // 返回新的上传任务
  }

  /** 启动文件上传的异步函数 该函数负责从待上传队列中启动文件上传任务，并管理并发上传的数量 */
  async function startUpload() {
    // 限制可同时上传的文件个数
    if (activeUploads.value.size >= 3) return;
    // 获取待上传的文件
    const pendingTasks = tasks.value.filter(
      t => t.status === UploadStatus.Pending && !activeUploads.value.has(t.paperId)
    );

    // 如果没有待上传的文件，则直接返回
    if (pendingTasks.length === 0) return;

    // 获取第一个待上传的文件
    const task = pendingTasks[0];
    task.status = UploadStatus.Uploading;
    activeUploads.value.add(task.paperId);

    // 计算文件总片数
    const totalChunks = Math.ceil(task.totalSize / chunkSize);

    try {
      if (task.uploadedChunks.length === totalChunks) {
        const success = await mergeFile(task);
        if (!success) throw new Error('文件合并失败');
        return;
      }

      const pendingChunkIndexes: number[] = [];
      for (let i = 0; i < totalChunks; i += 1) {
        if (!task.uploadedChunks.includes(i)) {
          pendingChunkIndexes.push(i);
        }
      }

      await uploadChunksInParallel(task, pendingChunkIndexes);

      const updatedTask = tasks.value.find(t => t.paperId === task.paperId);
      if (!updatedTask) return;

      if (updatedTask.uploadedChunks.length !== totalChunks) {
        throw new Error('分片上传未完成');
      }

      const success = await mergeFile(updatedTask);
      if (!success) throw new Error('文件合并失败');
    } catch (e) {
      console.error('%c [ 👉 upload error 👈 ]-168', 'font-size:16px; background:#94cc97; color:#d8ffdb;', e);
      // 如果上传失败，则将任务状态设置为中断
      const index = tasks.value.findIndex(t => t.paperId === task.paperId);
      tasks.value[index].status = UploadStatus.Break;
    } finally {
      // 无论成功或失败，都从活跃队列中移除
      activeUploads.value.delete(task.paperId);
      // 继续下一个任务
      startUpload();
    }
  }

  return {
    tasks,
    activeUploads,
    enqueueUpload,
    startUpload
  };
});
