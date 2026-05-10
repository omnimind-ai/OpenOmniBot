import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart'; // 修改：引入 ScrollDirection
import 'package:flutter_switch/flutter_switch.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/edit_profile/edit_profile_page.dart';
import 'package:ui/features/my/pages/my/widgets/memory_section.dart';
import 'package:ui/features/my/pages/my/widgets/profile_section.dart';
import 'package:ui/features/my/pages/my/widgets/setting_section.dart';
import 'package:ui/features/my/pages/my/widgets/setting_tile.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/features/task/pages/execution_history/trajectory_page.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/utils/cache_util.dart';

class MyPage extends StatefulWidget {
  const MyPage({super.key});

  @override
  State<MyPage> createState() => MyPageState();
}

class MyPageState extends State<MyPage> with WidgetsBindingObserver {
  bool vibrationEnabled = true;
  int avatarIndex = 0;
  String nickname = '';
  final List<String> presetAvatars = [
    'assets/avatar/default_avatar1.png',
    'assets/avatar/default_avatar2.png',
    'assets/avatar/default_avatar3.png',
    'assets/avatar/default_avatar4.png',
    'assets/avatar/default_avatar5.png',
    'assets/avatar/default_avatar6.png',
  ];

  MemorySummary? memorySummary;
  final ScrollController _scrollController = ScrollController();
  bool _isMemorySectionExpanded = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadVibrationState();
    _loadUserData();
    _loadMemorySummary();
    _scrollController.addListener(_onScroll);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _scrollController.removeListener(_onScroll);
    _scrollController.dispose();
    super.dispose();
  }

  // ✅ 应用从后台回到前台时刷新
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      print('✅ ExecutionRecordPage resumed - reloading memory summary');
      _loadMemorySummary();
    }
  }

  bool _handleScrollNotification(ScrollNotification notification) {
    if (notification.metrics.maxScrollExtent == 0 &&
        notification is UserScrollNotification &&
        notification.direction != ScrollDirection.idle) {
      if (notification.direction == ScrollDirection.reverse &&
          _isMemorySectionExpanded) {
        setState(() => _isMemorySectionExpanded = false);
      } else if (notification.direction == ScrollDirection.forward &&
          !_isMemorySectionExpanded) {
        setState(() => _isMemorySectionExpanded = true);
      }
    }
    return false;
  }

  void _onScroll() {
    final userScrollDirection = _scrollController.position.userScrollDirection;
    final currentOffset = _scrollController.offset;

    // 只有在用户主动滚动时才响应（排除回弹动画）
    if (userScrollDirection != ScrollDirection.idle) {
      // print('User scroll direction: $userScrollDirection, current offset: $currentOffset');
      // 向下滑动且偏移量大于阈值时收起
      if (userScrollDirection == ScrollDirection.reverse &&
          currentOffset > 50) {
        if (_isMemorySectionExpanded) {
          setState(() {
            _isMemorySectionExpanded = false;
          });
        }
      }
      // 向上滑动且偏移量接近0
      else if (userScrollDirection == ScrollDirection.forward &&
          currentOffset < 20) {
        if (!_isMemorySectionExpanded) {
          setState(() {
            _isMemorySectionExpanded = true;
          });
        }
      }
    }
  }

  Future<void> _loadVibrationState() async {
    try {
      final enabled = await CacheUtil.getBool("app_vibrate");
      setState(() {
        vibrationEnabled = enabled;
      });

      print('Vibration state loaded: $vibrationEnabled');
    } catch (e) {
      print('Error loading vibration state: $e');
    }
  }

  Future<void> _loadUserData() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final savedNickname = prefs.getString('nickname') ?? '';
      if (!mounted) return;
      setState(() {
        avatarIndex = prefs.getInt('avatarIndex') ?? 0;
        nickname = savedNickname;
      });
    } catch (e) {
      print('Error loading user data: $e');
    }
  }

  Future<void> refreshMemorySummary() async {
    print('Refreshing memory summary...');
    await _loadMemorySummary();
  }

  Future<void> _loadMemorySummary() async {
    try {
      final executionCount = await CacheUtil.getExecutionRecordCountByTitle();
      if (executionCount.isEmpty) {
        if (mounted) {
          setState(() {
            memorySummary = null;
          });
        }
        return;
      }
      // 将数据降序排列
      executionCount.sort((a, b) => b.count.compareTo(a.count));

      // 构建 tips 字符串,取前三多的title
      String tips = executionCount
          .take(3)
          .map((item) => '· ${item.title} ${item.count} 次')
          .join('\n');

      String sum = '本月你是一个惜时如金的天命人';

      setState(() {
        memorySummary = MemorySummary(
          title:
              '本月共执行 ${executionCount.fold<int>(0, (sum, item) => sum + item.count)} 件事，你可以在这查看',
          tips: tips,
          sum: sum,
        );
      });
    } catch (e) {
      print('Error loading memory summary: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final bg = Colors.white;
    final displayName = nickname.trim();

    return Scaffold(
      backgroundColor: bg,
      body: SafeArea(
        top: false,
        child: Stack(
          children: [
            Positioned.fill(
              top: 0,
              left: 0,
              right: 0,
              child: Container(
                decoration: BoxDecoration(
                  image: DecorationImage(
                    image: AssetImage('assets/my/bg.png'),
                    fit: BoxFit.fitWidth,
                    alignment: Alignment.topCenter,
                  ),
                ),
              ),
            ),
            Container(
              margin: const EdgeInsets.only(top: 151),
              padding: const EdgeInsets.only(top: 87),
              decoration: const BoxDecoration(
                gradient: LinearGradient(
                  colors: [Color(0x99FFFFFF), Color(0xFFFFFFFF)],
                ),
                borderRadius: BorderRadius.only(
                  topLeft: Radius.circular(8),
                  topRight: Radius.circular(8),
                ),
              ),
              child: Column(
                children: [
                  // MemorySection 固定在顶部
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 24),
                    child: AnimatedContainer(
                      duration: const Duration(milliseconds: 300),
                      curve: Curves.easeInOut,
                      child: MemorySection(
                        memorySummary: _isMemorySectionExpanded
                            ? memorySummary
                            : null,
                        onTap: () async {
                          Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (context) =>
                                  TrajectoryPage(),
                            ),
                          ).then((_) {
                            _loadMemorySummary();
                          });
                        },
                      ),
                    ),
                  ),
                  SizedBox(height: _isMemorySectionExpanded ? 13 : 7),
                  Expanded(
                    child: NotificationListener<ScrollNotification>(
                      onNotification: _handleScrollNotification,
                      child: ListView(
                        controller: _scrollController,
                        padding: EdgeInsets.symmetric(horizontal: 24),
                        children: [
                          // 第一组： 震动
                          SettingSection(
                            children: [
                              SettingTile(
                                title: '震动反馈',
                                trailing: FlutterSwitch(
                                  width: 44.8,
                                  height: 25.0,
                                  toggleSize: 15.3,
                                  padding: 4.8,
                                  activeColor: AppColors.primaryBlue,
                                  inactiveColor:
                                      AppColors.fillStandardSecondary,
                                  value: vibrationEnabled,
                                  borderRadius: 28.75,
                                  onToggle: (val) async {
                                    await CacheUtil.cacheBool(
                                      "app_vibrate",
                                      val,
                                    );
                                    setState(() {
                                      vibrationEnabled = val;
                                    });
                                  },
                                ),
                                showChevron: false,
                                onTap: () {},
                              ),
                              SettingTile(
                                title: 'MCP 工具',
                                onTap: () =>
                                    GoRouterManager.push('/home/mcp_tools'),
                              ),
                            ],
                          ),
                          const SizedBox(height: 8),

                          // 第二组：反馈 / 关于
                          SettingSection(
                            children: [
                              SettingTile(
                                title: '意见反馈',
                                onTap: () {
                                  GoRouterManager.push('/my/feedback');
                                },
                              ),
                              SettingTile(
                                title: '关于小万',
                                onTap: () {
                                  GoRouterManager.push('/my/about');
                                },
                              ),
                            ],
                          ),
                          const SizedBox(height: 32),
                          const SizedBox(height: 40),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
            // 头像组件 - 位于背景和内容区域交界处
            Positioned(
              top: 70,
              left: 0,
              right: 0,
              child: Center(
                child: ProfileSection(
                  username: displayName.isEmpty ? '用户名' : displayName,
                  avatarUrl: presetAvatars[avatarIndex],
                  onTap: () {
                    Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (context) => EditProfilePage(
                          initialAvatarIndex: avatarIndex,
                          initialNickname: displayName,
                        ),
                      ),
                    ).then((result) {
                      if (result != null) {
                        setState(() {
                          avatarIndex = result['avatarIndex'] ?? avatarIndex;
                          nickname = (result['nickname'] ?? nickname)
                              .toString();
                        });
                      }
                    });
                  },
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
