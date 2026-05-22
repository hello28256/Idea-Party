# Discussion Mode Testing Checklist

## Backend (requires restart after changes)
```bash
cd server
./mvnw compile
./mvnw spring-boot:run
```

## Frontend
```bash
cd client
npm run dev
```

## Manual Test Scenarios

### 1. Basic Discussion Flow
1. Open a room in Discussion Mode
2. Send message: "AI会取代程序员吗？"
3. Verify Moderator selects 1-2 characters
4. Verify AI messages are limited (max 3 per round)
5. Verify Moderator invites user after 2-3 messages
6. Verify discussion pauses waiting for user input

### 2. User Interruption
1. Start a discussion
2. While AI is speaking, send a new message
3. Verify current discussion queue is cleared
4. Verify Moderator re-analyzes and selects new characters
5. Verify new discussion starts from the user's new message

### 3. Pause/Resume
1. Start a discussion
2. Click "暂停讨论"
3. Verify AI stops speaking
4. Verify status shows "已暂停"
5. Click "继续讨论"
6. Verify AI resumes speaking

### 4. Status Indicator
1. Start discussion
2. Verify status shows "主持人分析中..." (MODERATING)
3. When AI speaks, verify status shows "讨论中" (SPEAKING)
4. When waiting for user, verify status shows "等待你参与" (WAITING_FOR_USER)

### 5. Moderator Messages
1. Start discussion
2. Verify Moderator selection message appears
3. Verify Moderator invite message appears after AI speaks