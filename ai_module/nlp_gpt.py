import requests
from urllib3.exceptions import InsecureRequestWarning
import time
from utils import util

# 禁用不安全请求警告
requests.packages.urllib3.disable_warnings(category=InsecureRequestWarning)

# 基础 URL
BASE_URL = "http://66.135.12.49:8089/chat/"

def question(cont):
    session = requests.Session()
    session.verify = False
    starttime = time.time()

    try:
        # 发送 GET 请求
        response = session.get(BASE_URL + cont)
        response.raise_for_status()  # 检查响应状态码是否为 200

        # 获取响应文本
        response_text = response.text
    except requests.exceptions.RequestException as e:
        # 请求失败时的异常处理
        response_text = "抱歉，我现在太忙了，休息一会，请稍后再试。"
        print(f"请求失败: {e}")

    util.log(1, "接口调用耗时: " + str(time.time() - starttime))
    return response_text

if __name__ == "__main__":
    # 测试
    for i in range(1):
        query = "爱情是什么"
        response = question(query)
        print("\nThe result is:", response)
