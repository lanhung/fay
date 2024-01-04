
import openai
import os

# 设置代理（如果需要）
#os.environ["http_proxy"] = "http://localhost:7890"
#os.environ["https_proxy"] = "http://localhost:7890"

# 设置 OpenAI API 密钥
openai.api_key = "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"
#openai.proxy = "http://127.0.0.1:7890"

# 上传文件的函数
def upload_file(file_path, purpose):
    with open(file_path, 'rb') as file_data:
        uploaded_file = openai.File.create(file=file_data, purpose=purpose)
    return uploaded_file

# 创建 AI 助手的函数
def create_assistant(name, instructions, model, file_ids):
    assistant = openai.Assistant.create(
        name=name,
        instructions=instructions,
        tools=[{"type": "retrieval"}],
        model=model,
        file_ids=file_ids
    )
    return assistant

# 发送查询并获取响应的函数
def send_query_get_response(assistant_id, query):
    thread = openai.Thread.create()
    message = openai.ThreadMessage.create(
        thread_id=thread.id,
        role="user",
        content=query
    )

    run = openai.ThreadRun.create(
        thread_id=thread.id,
        assistant_id=assistant_id
    )

    while True:
        run_status = openai.ThreadRun.retrieve(thread_id=thread.id, run_id=run.id)
        if run_status.status == "completed":
            messages = openai.ThreadMessage.list(thread_id=thread.id)
            latest_message = messages.data[0]
            response_text = latest_message.content[0].text.value
            break

    return response_text

# 示例用法
# 上传文件
uploaded_file = upload_file("C://Users//GM//Desktop//jiandingfake.txt", "assistants")

# 创建助手
assistant = create_assistant("Futuresmart AI Assistent", "有关人体损伤鉴定的查询", "gpt-4-1106-preview", [uploaded_file.id])

# 发送查询并获取响应
response = send_query_get_response(assistant.id, "你的查询内容")
print(response)
