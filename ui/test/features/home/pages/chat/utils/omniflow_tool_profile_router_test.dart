import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/utils/omniflow_tool_profile_router.dart';

void main() {
  test('routes explicit RunLog management requests to function profile', () {
    expect(
      omniflowToolProfileForMessage('帮我把上一条 runlog 注册了'),
      'function_management',
    );
    expect(
      omniflowToolProfileForMessage('保存刚才的轨迹为复用指令'),
      'function_management',
    );
    expect(
      omniflowToolProfileForMessage('帮我用刚才录制的联系人姓名轨迹填写 Eve'),
      'function_management',
    );
    expect(
      omniflowToolProfileForMessage('调用上一条复用指令，参数是 Bob'),
      'function_management',
    );
    expect(
      omniflowToolProfileForMessage('reuse the last function with name Dora'),
      'function_management',
    );
    expect(
      omniflowToolProfileForMessage('list functions'),
      'function_management',
    );
  });

  test('keeps ordinary chat and phone tasks on the default profile', () {
    expect(omniflowToolProfileForMessage('打开蓝牙'), isNull);
    expect(omniflowToolProfileForMessage('总结一下刚才的聊天'), isNull);
    expect(omniflowToolProfileForMessage('function 是什么概念'), isNull);
  });
}
