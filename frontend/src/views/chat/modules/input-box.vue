<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue';
const chatStore = useChatStore();
const { input, list, wsStatus } = storeToRefs(chatStore);

const latestMessage = computed(() => {
  return list.value[list.value.length - 1] ?? {};
});

const isSending = computed(() => {
  return (
    latestMessage.value?.role === 'assistant' && ['loading', 'pending'].includes(latestMessage.value?.status || '')
  );
});

const sendable = computed(
  () => (!input.value.message && !isSending) || ['CLOSED', 'CONNECTING'].includes(wsStatus.value)
);

// 相关问题推荐
const showRecommendations = ref(false);
const recommendations = ref<string[]>([]);

// 预设问题库
const presetQuestions = [
  '如何使用知识库功能？',
  '如何上传文档到知识库？',
  '如何查看历史聊天记录？',
  '如何优化搜索结果？',
  '如何设置文档权限？',
  '如何导出聊天记录？',
  '如何使用文件分析功能？',
  '如何管理组织标签？'
];

// 根据输入内容推荐相关问题
const updateRecommendations = () => {
  const inputText = input.value.message.toLowerCase().trim();
  if (inputText.length < 2) {
    showRecommendations.value = false;
    recommendations.value = [];
    return;
  }

  // 简单的关键词匹配
  const matchedQuestions = presetQuestions.filter(question => 
    question.toLowerCase().includes(inputText)
  );

  if (matchedQuestions.length > 0) {
    recommendations.value = matchedQuestions.slice(0, 5); // 最多显示5个推荐
    showRecommendations.value = true;
  } else {
    showRecommendations.value = false;
  }
};

// 监听输入变化
watch(() => input.value.message, updateRecommendations);

// 发送推荐问题
const sendRecommendedQuestion = (question: string) => {
  input.value.message = question;
  showRecommendations.value = false;
  handleSend();
};

const handleSend = async () => {
  //  判断是否正在发送, 如果发送中，则停止ai继续响应
  if (isSending.value) {
    const { error, data } = await request<Api.Chat.Token>({ url: 'chat/websocket-token', baseURL: 'proxy-api' });
    if (error) return;

    chatStore.wsSend(JSON.stringify({ type: 'stop', _internal_cmd_token: data.cmdToken }));

    list.value[list.value.length - 1].status = 'finished';
    if (!latestMessage.value.content) list.value.pop();
    return;
  }

  list.value.push({
    content: input.value.message,
    role: 'user'
  });
  chatStore.wsSend(input.value.message);
  list.value.push({
    content: '',
    role: 'assistant',
    status: 'pending'
  });
  input.value.message = '';
  showRecommendations.value = false;
};

const inputRef = ref();
// 手动插入换行符（确保所有浏览器兼容）
const insertNewline = () => {
  const textarea = inputRef.value;
  const start = textarea.selectionStart;
  const end = textarea.selectionEnd;

  // 在光标位置插入换行符
  input.value.message = `${input.value.message.substring(0, start)}
${input.value.message.substring(end)}`;

  // 更新光标位置（在插入的换行符之后）
  nextTick(() => {
    textarea.selectionStart = start + 1;
    textarea.selectionEnd = start + 1;
    textarea.focus(); // 确保保持焦点
  });
};

// ctrl + enter 换行
// enter 发送
const handShortcut = (e: KeyboardEvent) => {
  if (e.key === 'Enter') {
    e.preventDefault();

    if (!e.shiftKey && !e.ctrlKey) {
      handleSend();
    } else insertNewline();
  }
};

// 点击外部关闭推荐
const handleClickOutside = (e: MouseEvent) => {
  const target = e.target as HTMLElement;
  if (!target.closest('.input-container')) {
    showRecommendations.value = false;
  }
};

// 监听点击事件
onMounted(() => {
  document.addEventListener('click', handleClickOutside);
});

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside);
});
</script>

<template>
  <div class="input-container relative w-full b-1 b-#1c1c1c20 bg-#fff card-wrapper dark:bg-#1c1c1c">
    <!-- 相关问题推荐 -->
    <div v-if="showRecommendations && recommendations.length > 0" class="absolute top-0 left-0 right-0 z-10 bg-white p-4 b-b-1 b-#e8e8e8 dark:bg-#2c2c2c dark:b-b-1 dark:b-#444">
      <NText class="text-14px mb-2 block color-gray-600 dark:color-gray-400">相关问题推荐：</NText>
      <div class="flex flex-col gap-2">
        <NButton 
          v-for="(question, index) in recommendations" 
          :key="index" 
          quaternary 
          class="text-left justify-start"
          @click="sendRecommendedQuestion(question)"
        >
          {{ question }}
        </NButton>
      </div>
    </div>
    
    <div class="p-4">
      <textarea
        ref="inputRef"
        v-model.trim="input.message"
        placeholder="给 派聪明 发送消息"
        class="min-h-10 w-full cursor-text resize-none b-none bg-transparent color-#333 caret-[rgb(var(--primary-color))] outline-none dark:color-#f1f1f1"
        @keydown="handShortcut"
      />
      <div class="flex items-center justify-between pt-2">
        <div class="flex items-center text-18px color-gray-500">
          <NText class="text-14px">连接状态：</NText>
        <icon-mdi-loading v-if="wsStatus === 'CONNECTING'" class="color-yellow" />
        <icon-mdi-check-circle v-else-if="wsStatus === 'OPEN'" class="color-green" />
        <icon-mdi-close-circle v-else class="color-red" />
        </div>
        <NButton :disabled="sendable" strong circle type="primary" @click="handleSend">
          <template #icon>
            <icon-mdi-stop v-if="isSending" />
            <icon-mdi-send v-else />
          </template>
        </NButton>
      </div>
    </div>
  </div>
</template>

<style scoped></style>
