import 'package:go_router/go_router.dart';
import '../../features/home/router_config.dart';
import '../../features/task/router_config.dart';
import '../../features/welcome/router_config.dart';
import '../../features/memory/router_config.dart';
import '../../features/my/router_config.dart';
import '../../features/workbench/router_config.dart';

class AppRouterConfig {
  static List<GoRoute> getAllRoutes() {
    return [
      ...homeRoutes,
      ...taskRoutes,
      ...welcomeRoutes,
      ...memoryRoutes,
      ...myRoutes,
      ...workbenchRoutes,
    ];
  }
}
