# Guild API Integration Guide

이 문서는 퀘스트 플러그인에서 길드 정보를 연동하는 방법입니다.

## 1) plugin.yml 설정

길드 플러그인 로딩 후 접근하도록 `depend` 또는 `softdepend`를 추가하세요.

```yml
depend: [Guild]
```

또는

```yml
softdepend: [Guild]
```

## 2) API 조회

`Bukkit ServicesManager`에서 `GuildApi`를 가져옵니다.

```java
import com.guild.plugin.GuildApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

RegisteredServiceProvider<GuildApi> rsp = Bukkit.getServicesManager().getRegistration(GuildApi.class);
GuildApi guildApi = (rsp != null) ? rsp.getProvider() : null;
```

## 3) 자주 쓰는 예시

### 플레이어 길드명 조회

```java
String guildName = guildApi.getGuildName(player.getUniqueId()).orElse(null);
```

### 길드 리더 여부

```java
boolean isLeader = guildApi.isLeader(player.getUniqueId());
```

### 길드 멤버 목록 조회

```java
List<GuildApi.GuildMember> members = guildApi.getMembers(guildName);
for (GuildApi.GuildMember member : members) {
    // member.uuid(), member.name(), member.online(), member.joinedAt() ...
}
```

### 길드 요약 정보

```java
guildApi.getGuildProfileByPlayer(player.getUniqueId()).ifPresent(profile -> {
    String name = profile.name();
    int memberCount = profile.memberCount();
    int maxMembers = profile.maxMembers();
    boolean autoJoin = profile.autoJoin();
});
```

## 4) 현재 제공 API

- `boolean hasGuild(UUID playerId)`
- `Optional<String> getGuildName(UUID playerId)`
- `Optional<GuildProfile> getGuildProfileByPlayer(UUID playerId)`
- `Optional<GuildProfile> getGuildProfileByName(String guildName)`
- `boolean isLeader(UUID playerId)`
- `List<GuildMember> getMembers(String guildName)`
- `List<UUID> getMemberIds(String guildName)`

## 5) 주의 사항

- `guildApi == null` 가능성을 항상 처리하세요.
- 서버 리로드/플러그인 재시작 중에는 서비스가 잠시 비어 있을 수 있습니다.
- 시간값(`lastSeen`, `joinedAt`)은 epoch millis 기준입니다.
