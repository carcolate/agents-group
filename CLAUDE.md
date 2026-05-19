# 项目开发规范与代码习惯

## 项目结构规范

### 标准包结构

```
src/main/java/com/carcolate/carshop/
├── config/                # 配置层（拦截器、过滤器、数据库配置）
├── controller/            # 控制器层（按业务场景分组）
│   ├── manage/            # 管理后台API
├── service/               # 服务层
│   └── impl/              # 服务实现
├── dao/                   # 数据访问层（复杂查询）
├── mapper/                # MyBatis Mapper接口
├── domain/                # 实体类（与数据库表一一对应）
├── response/              # 响应封装类
├── tools/                 # 工具类
├── cfg/                   # 配置与枚举工具
└── Application.java       # 启动类

src/main/resources/
├── application.properties       # 主配置
├── application-local.properties # 本地环境
├── application-dev.properties   # 开发环境
├── mapper/                      # MyBatis XML映射文件
```

---

## 分层架构规范

### 标准分层结构

```
┌─────────────────────────────────────┐
│  Controller Layer (控制器层)         │  ← API入口、参数校验、权限控制
├─────────────────────────────────────┤
│  Service Layer (服务层)              │  ← 业务逻辑、事务管理、跨DAO协调
├─────────────────────────────────────┤
│  DAO Layer (数据访问层)              │  ← 复杂查询、多表关联、缓存策略
├─────────────────────────────────────┤
│  Mapper Layer (ORM映射层)            │  ← 基础CRUD、实体映射
├─────────────────────────────────────┤
│  Domain Layer (实体层)               │  ← 实体、枚举
└─────────────────────────────────────┘
```

### 各层职责

| 包名 | 职责说明 |
|------|----------|
| `controller` | API入口，按场景分组，参数校验，返回统一响应 |
| `service` | 业务逻辑编排，事务边界，继承 `IService` |
| `dao` | 复杂查询、多表关联、业务定制数据操作 |
| `mapper` | MyBatis-Plus基础CRUD，继承 `BaseMapper<Entity>` |
| `domain` | 数据库实体，`@TableName`、`@TableId`注解 |

---

## 实体类规范 (domain/)

### 基础规范
- 类名与表名对应：`Car` → `tb_cars`
- 注解：`@TableName(value = "tb_xxx")`
- Lombok：`@Data` 生成 getter/setter
- 主键：`@TableId(type = IdType.INPUT)`
- 实现 `Serializable`

### 字段命名习惯

| 数据库字段 | Java 字段 | 说明 |
|-----------|----------|------|
| `vin` | `vin` | 车架号 |
| `brand_id` | `brandId` | 品牌 ID |
| `series_id` | `seriesId` | 车型 ID |
| `model_id` | `modelId` | 型号 ID |
| `condition_type` | `conditionType` | 新旧类型 (枚举) |
| `created_at` | `createdAt` | 创建时间 |
| `updated_at` | `updatedAt` | 更新时间 |

### 内置枚举写法

```java
@AllArgsConstructor
@Getter
private enum ConditionType implements EnumInterface<String> {
    New("New"), Used("Used");
    private final String value;
    
    @Override
    public String getCode() {
        return this.value;
    }
}
```

### 数据类型习惯

| 场景 | 类型 | 示例 |
|------|------|------|
| 主键 | `Long` | `private Long id` |
| 金额/里程 | `BigDecimal` | `private BigDecimal mileage` |
| 时间 | `Instant` | `private Instant createdAt` |
| 年份 | `Object` | `private Object modelYear` |
| 数量 | `Integer` | `private Integer cylinderCount` |
| ID 字段 | `long` | `private long brandId` |
| 状态 | `Integer` | `private Integer status` |

### 注释规范

```java
/**
 * 汽车基础信息表
 * @TableName tb_cars
 */
@Data
@TableName(value = "tb_cars")
public class Car implements Serializable {
    /**
     * 车架号 (VIN)
     */
    private String vin;
    
    /**
     * 状态：1-在库，2-已预订，3-已售出，4-运输中
     */
    private Integer status;
}
```

---

## Mapper 层规范

### 接口定义

```java
public interface XxxMapper extends BaseMapper<Xxx> {
    // 列表查询 (支持分页)
    List<Xxx> List(@Param("p") Xxx xxx);
    IPage<Xxx> List(IPage<Xxx> page, @Param("p") Xxx xxx);
}
```

### XML 查询条件习惯

```xml
<select id="List" resultType="com.carcolate.carshop.domain.Xxx">
    SELECT xxx.* FROM tb_xxx AS xxx
    <where>
        <if test="p.id!=null">
            AND xxx.id = #{p.id}
        </if>
        <if test="p.xxxId>0">
            AND t.xxx_id = #{p.xxxId}
        </if>
        <if test="p.status!=null">
            AND t.status = #{p.status}
        </if>
    </where>
</select>
```

### SQL 编写习惯
- 主表使用简短别名：`tb_cars AS car`
- 主表字段：`car.xxx`
- 条件参数：`#{p.xxx}`
- 数值型判断：`>0`，对象判断：`!=null`

---

## Service 层规范

### 接口

```java
public interface XxxService extends IService<Xxx> {
}
```

### 实现类

```java
@Service
public class XxxServiceImpl extends ServiceImpl<XxxMapper, Xxx> implements XxxService {
}
```

### 业务写法
- 默认继承 `ServiceImpl` 获得基础 CRUD
- 自定义方法在接口和实现类中扩展
- 当前项目保持简洁，无额外业务逻辑

---

## 统一响应封装

```java
public class Rsp<T> {
    private int code;       // 状态码（0=成功，非0=错误码）
    private String msg;     // 提示消息
    private T data;         // 业务数据
    private T extra;        // 扩展数据（可选）
    private long total;     // 分页总数（可选）

    public static <T> Rsp<T> success(T data) { ... }
    public static <T> Rsp<T> error(CodeMsg codeMsg) { ... }
}
```

---

## 工具类规范 (cfg/)

### 枚举接口

```java
public interface EnumInterface<T> {
    T getCode();
}
```

### 枚举转换

```java
// 返回 null (不抛异常)
T t = EnumUtil.getByValue(code, XxxEnum.class);

// 抛异常 (参数校验)
T t = EnumUtilCatch.getByValue(code, XxxEnum.class);
```

---

## 配置文件习惯

### 多环境配置

```
application.properties       # 主配置 (端口、环境变量占位)
application-local.properties # 本地环境 (Druid、Redis、MyBatis 配置)
application-dev.properties   # 开发环境 (远程数据库)
```

### MyBatis Plus 配置

```properties
mybatis-plus.mapper-locations=classpath*:mapper/*.xml
mybatis-plus.global-config.db-config.id-type=auto
mybatis-plus.global-config.db-config.field-strategy=NOT_EMPTY
mybatis-plus.configuration.map-underscore-to-camel-case=true
mybatis-plus.configuration.default-enum-type-handler=org.apache.ibatis.type.EnumOrdinalTypeHandler
mybatis-plus.configuration.call-setters-on-nulls=true
```

---

## 控制器分组规范

```
controller/
├── manage/              # 管理后台（需要管理员权限）
│   ├── ManageCarController.java
│   ├── ManageExchangeController.java
│   ├── ManageMapController.java
├── mockController/      # Mock测试API
│   └── MockController.java
├── FileUpdateController.java    # 文件上传
├── CarController.java           # 公共汽车API
├── FrontCarDatabaseController.java  # 前端车辆数据库API
```

**命名规范**:
- Controller类名：`{Group}{Entity}Controller`，如 `ManageCarController`
- 方法名：动词开头，如 `list`、`get`、`add`、`update`

---

## 新增业务模块步骤

1. **定义实体**: 在 `domain/` 创建实体类 + 内置枚举
2. **定义Mapper**: 在 `mapper/` 创建接口，继承 `BaseMapper`
3. **定义Mapper XML**: 在 `resources/mapper/` 创建SQL映射
4. **定义Service**: 在 `service/` 和 `service/impl/` 创建接口和实现
5. **定义Controller**: 在对应分组包下创建Controller

---

## 技术栈

| 类别 | 技术 |
|------|------|
| ORM | MyBatis-Plus |
| 连接池 | Druid |
| 工具库 | Hutool、Lombok |
| 主键策略 | IdType.INPUT / AUTO |

---

## 前端 Admin 规范 (CarShop-Admin)

### API 调用
- 所有 API 统一走后端接口 `/api` 前缀
- 通过 Vite proxy 转发到后端服务
- 响应格式遵循 `ApiRsp<T>` 结构

### 环境配置
- `.env.base` - 基础开发配置
- `.env.dev` - 开发环境打包
- `.env.pro` - 生产环境打包

### 代理配置
```typescript
server: {
  proxy: {
    '/api': {
      target: 'http://127.0.0.1:7892',
      changeOrigin: true,
      rewrite: (path) => path.replace(/^\/api/, '')
    }
  }
}
```