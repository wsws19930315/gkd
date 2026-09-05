# gkd-app 模块架构

`gkd-app` 采用“功能纵向切片 + 明确的数据和平台边界”。依赖方向如下：

```text
App / MainActivity
        │
        ▼
feature ─────► domain
   │             │
   ├──────────► data
   │
   └──────────► platform

service / receiver ─► data、domain、platform
```

## 目录职责

- `app/`：生产实例组装入口。进程级唯一组件直接声明为 `object`；`AppContainer` 只创建需要独立构造的 `SettingsRepository` 和 `SnapshotRepository`，不放业务逻辑，也不包装 DAO。
- `core/`：跨层共享且不依赖 Android UI 的基础状态和值类型。
- `feature/`：按用户功能组织 Page、Route、ViewModel 和功能内组件。当前包含 `log`、`snapshot`、`subscription`、`settings`。
- `domain/`：可独立验证的业务规则与值对象，例如规则组启用策略。不得依赖 Compose、Activity、Service 或 DAO。
- `data/`：持久化、网络、文件及跨数据源一致性边界。单表访问直接依赖 Room DAO；只有需要协调状态、并发、文件或多个数据源时才引入 Repository、Manager 或 Service。
- `platform/`：Android 平台能力的统一入口，例如 Service 启停控制；不得依赖页面 ViewModel。
- `ui/component`、`ui/share`、`ui/style`：跨功能复用的无业务写入 UI 基础设施。
- `service/`、`a11y/`、`notif/`、`priv/`：Android 已注册组件及其运行时实现。为保持系统组件类名兼容暂不改包名，但通过 `platform/` 与 UI 解耦。

## 状态与写入边界

| 场景 | 读取 | 写入 |
| --- | --- | --- |
| 设置 | `SettingsRepository` 暴露的只读 `StateFlow` | `SettingsRepository.update/replace` |
| 订阅 | `SubscriptionRepository.snapshotFlow`、`SubscriptionState` 的派生状态、`Db.subsItemDao` 的冷 `Flow` | `SubscriptionRepository` 编排订阅用例，`SubscriptionPersistence` 保证文件与数据库补偿一致性，`SubscriptionFileStore` 负责原子文件写入，单表字段更新直接使用 DAO |
| 规则配置 | Room DAO 的冷 `Flow` | 单表写入使用 DAO；跨规则业务操作使用 `RuleGroupConfigService` |
| 日志 | 对应 Room DAO 的 Flow/PagingSource | 对应 Room DAO 的插入、删除、裁剪方法 |
| 快照 | `SnapshotRepository.snapshots()` | `SnapshotRepository` 的文件与数据库原子操作 |
| 备份 | `BackupManager` 读取各数据源 | `BackupManager` 编排导入、校验与恢复 |
| Service | Service 自身只维护运行状态 | 页面请求权限后调用 `ServiceController`；前台保活悬浮窗统一由 `KeepAliveOverlayCoordinator` 协调 |

Composable 不得直接访问 `Db`、文件或 Service 生命周期。ViewModel 可以直接使用 `Db` 提供的职责单一 DAO，并负责聚合页面一致性所需状态；不要为了形式上的依赖注入把全局唯一 DAO 塞入 ViewModel 构造器。跨数据源写入和具有业务规则的操作交给 Repository、Manager 或 Service。Service、Receiver 和无障碍运行时不得引用页面 ViewModel。

## 新代码放置

1. 新页面优先放入对应 `feature/<name>`；只有两个以上功能使用的纯 UI 才进入 `ui/component` 或 `ui/share`。
2. 跨页面业务判断进入 `domain`，并优先写纯函数行为测试。
3. 单表查询或写入直接使用对应 DAO，不新增一对一转发层；网络、文件、缓存、并发控制和跨表一致性操作进入 `data/<name>` 下职责明确的 Repository、Manager 或 Service。
4. Android 权限、Service、通知及系统 API 适配进入 `platform` 或保留在已注册组件包中，通过窄接口向上提供能力。
5. 进程级唯一且依赖固定的组件直接声明为 `object`；只有确实需要独立构造的生产实例才由 `AppContainer` 创建。DAO 直接由 `Db` 提供，不在容器中重复暴露。

## 迁移中的兼容边界

- `service` 包名承载 Manifest、无障碍服务和快捷设置组件身份，重命名会使系统授权或磁贴失效，因此只迁移调用边界，不修改组件类名。
- `RawSubscription` 和数据库配置实体仍是历史共享模型；规则汇总已经下沉到 `domain/rule/RuleSummaryBuilder`，新逻辑不要再回填到应用级状态容器。
