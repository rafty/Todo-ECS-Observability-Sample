import http from 'k6/http';
import { check, sleep } from 'k6';

// なぜ必要か: JSONファイル読み込みとパース処理を共通化し、設定不備時に原因を明確にするため。
function readJsonFile(path, fileLabel) {
  try {
    return JSON.parse(open(path));
  } catch (error) {
    throw new Error(`${fileLabel} could not be loaded from ${path}: ${String(error)}`);
  }
}

// なぜ必要か: ユーザーごとのJWTを外部ファイルから読み込み、認証付きAPI負荷を再現するため。
const tokens = readJsonFile('../data/tokens.json', 'tokens.json');
// なぜ必要か: DLT環境に依存しない設定受け渡しのため、同梱config.jsonを読み込むため。
const config = readJsonFile('../data/config.json', 'config.json');
// なぜ必要か: 末尾スラッシュ有無の差分でURL結合が壊れないようにするため。
// 注意点: 優先順位は `__ENV.BASE_URL` > `config.baseUrl` とする。
const BASE_URL = (__ENV.BASE_URL || config.baseUrl || '').trim().replace(/\/$/, '');
const SLEEP_SECONDS = Number(__ENV.SLEEP_SECONDS || config.sleepSeconds || '0.2');
const MAX_PAGE_SIZE = Number(__ENV.MAX_PAGE_SIZE || config.maxPageSize || '100');
// なぜ必要か: 1実行あたりのデータ肥大化を抑制し、環境汚染を防ぐため。
const MAX_NEW_TODOS_PER_USER = 50;
// なぜ必要か: 要件の「各ユーザー初期20件」をシナリオで担保するため。
const INITIAL_TODOS_PER_USER = 20;

// なぜ必要か: DLT/K6実行時の同時数・時間・品質閾値を環境変数で制御可能にするため。
export const options = {
  vus: Number(__ENV.VUS || config.vus || '100'),
  duration: __ENV.DURATION || config.duration || '10m',
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<2000'],
  },
};

// なぜ必要か: ユーザーごとの既知Todo一覧/作成件数を保持し、他ユーザー操作を避けるため。
const stateByUserIndex = {};

// なぜ必要か: CRUD対象のランダム選択や説明文長の制御に共通利用するため。
function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

// なぜ必要か: description長の要件（100〜300文字）を満たすデータを安定生成するため。
function randomDescription(minLength, maxLength, salt) {
  const targetLength = randomInt(minLength, maxLength);
  const base =
    `loadtest description ${salt} `.repeat(Math.ceil(targetLength / 24)) +
    'abcdefghijklmnopqrstuvwxyz0123456789';
  return base.slice(0, targetLength);
}

// なぜ必要か: API異常時にJSONパース失敗でシナリオ全体が停止しないようにするため。
function parseJsonOrNull(response) {
  try {
    return response.json();
  } catch (_error) {
    return null;
  }
}

// なぜ必要か: VUをトークン配列へ対応付け、ユーザー別状態を初期化して再利用するため。
function getUserContext() {
  const userIndex = (__VU - 1) % tokens.length;
  const token = tokens[userIndex];
  if (!stateByUserIndex[userIndex]) {
    stateByUserIndex[userIndex] = {
      knownTodoIds: [],
      seeded: false,
      createdCount: 0,
      seq: 0,
    };
  }

  return {
    userIndex,
    token,
    state: stateByUserIndex[userIndex],
  };
}

// なぜ必要か: すべてのリクエストで同じ認証/Content-Typeヘッダを一元管理するため。
function authHeaders(accessToken) {
  return {
    Authorization: `Bearer ${accessToken}`,
    'Content-Type': 'application/json',
  };
}

// なぜ必要か: 一覧取得でユーザー所有Todo ID群を更新し、後続PUT/DELETE対象を限定するため。
function listTodos(userContext) {
  const url = `${BASE_URL}/api/todos?page=0&size=${MAX_PAGE_SIZE}&sort=updatedAt,desc`;
  const response = http.get(url, {
    headers: authHeaders(userContext.token.accessToken),
    tags: { name: 'GET /api/todos' },
  });

  check(response, {
    'GET /api/todos is 200': (r) => r.status === 200,
  });

  if (response.status === 200) {
    const json = parseJsonOrNull(response);
    if (json && Array.isArray(json.items)) {
      userContext.state.knownTodoIds = json.items
        .map((item) => item.id)
        .filter((id) => Number.isInteger(id));
    }
  }
}

// なぜ必要か: 更新前の現物確認やGET比率の実トラフィック再現に詳細取得を使うため。
function getTodo(userContext, todoId) {
  const response = http.get(`${BASE_URL}/api/todos/${todoId}`, {
    headers: authHeaders(userContext.token.accessToken),
    tags: { name: 'GET /api/todos/{id}' },
  });
  check(response, {
    'GET /api/todos/{id} is 200 or 404': (r) => r.status === 200 || r.status === 404,
  });
  return response;
}

// なぜ必要か: 作成上限を守りつつ、初期データ投入と通常POSTを同一ロジックで処理するため。
function createTodo(userContext, reason) {
  if (userContext.state.createdCount >= MAX_NEW_TODOS_PER_USER) {
    return null;
  }

  userContext.state.seq += 1;
  const payload = {
    title: `todo-${reason}-vu${__VU}-seq${userContext.state.seq}`,
    description: randomDescription(100, 300, `${__VU}-${userContext.state.seq}-${reason}`),
    completed: false,
  };

  const response = http.post(`${BASE_URL}/api/todos`, JSON.stringify(payload), {
    headers: authHeaders(userContext.token.accessToken),
    tags: { name: 'POST /api/todos' },
  });

  check(response, {
    'POST /api/todos is 201': (r) => r.status === 201,
  });

  if (response.status === 201) {
    const json = parseJsonOrNull(response);
    if (json && Number.isInteger(json.id)) {
      userContext.state.knownTodoIds.push(json.id);
      userContext.state.createdCount += 1;
      return json.id;
    }
  }
  return null;
}

// なぜ必要か: 既存データを取得して安全に更新し、想定外のデータ破壊を避けるため。
function updateTodo(userContext, todoId) {
  const detailResponse = getTodo(userContext, todoId);
  if (detailResponse.status !== 200) {
    return;
  }

  const todo = parseJsonOrNull(detailResponse);
  if (!todo) {
    return;
  }

  const payload = {
    title: todo.title || `todo-updated-vu${__VU}`,
    description: randomDescription(100, 300, `${todoId}-update`),
    completed: !Boolean(todo.completed),
  };

  const response = http.put(`${BASE_URL}/api/todos/${todoId}`, JSON.stringify(payload), {
    headers: authHeaders(userContext.token.accessToken),
    tags: { name: 'PUT /api/todos/{id}' },
  });

  check(response, {
    'PUT /api/todos/{id} is 200 or 404': (r) => r.status === 200 || r.status === 404,
  });
}

// なぜ必要か: 所有データのみ削除し、成功時はローカル状態からも即時除外するため。
function deleteTodo(userContext, todoId) {
  const response = http.del(`${BASE_URL}/api/todos/${todoId}`, null, {
    headers: authHeaders(userContext.token.accessToken),
    tags: { name: 'DELETE /api/todos/{id}' },
  });

  check(response, {
    'DELETE /api/todos/{id} is 204 or 404': (r) => r.status === 204 || r.status === 404,
  });

  if (response.status === 204) {
    userContext.state.knownTodoIds = userContext.state.knownTodoIds.filter((id) => id !== todoId);
  }
}

// なぜ必要か: 各ユーザーの初期Todo件数要件（20件）を最初の反復で満たすため。
function ensureInitialTodos(userContext) {
  if (userContext.state.seeded) {
    return;
  }

  listTodos(userContext);
  while (
    userContext.state.knownTodoIds.length < INITIAL_TODOS_PER_USER &&
    userContext.state.createdCount < MAX_NEW_TODOS_PER_USER
  ) {
    createTodo(userContext, 'seed');
  }
  listTodos(userContext);
  userContext.state.seeded = true;
}

// なぜ必要か: PUT/DELETE対象を既知IDからランダム選択し、操作偏りを減らすため。
function randomKnownTodoId(userContext) {
  if (userContext.state.knownTodoIds.length === 0) {
    return null;
  }
  const index = randomInt(0, userContext.state.knownTodoIds.length - 1);
  return userContext.state.knownTodoIds[index];
}

export function setup() {
  // なぜ必要か: 入力不足を初期段階で検知し、無効な負荷実行を防ぐため。
  if (!BASE_URL) {
    throw new Error(
      'BASE_URL is required. Set __ENV.BASE_URL or specify baseUrl in ../data/config.json.',
    );
  }
  // なぜ必要か: 不正な数値設定のまま実行されることを防ぎ、原因切り分けを容易にするため。
  if (!Number.isFinite(SLEEP_SECONDS) || SLEEP_SECONDS < 0) {
    throw new Error('SLEEP_SECONDS must be a number >= 0.');
  }
  if (!Number.isFinite(MAX_PAGE_SIZE) || MAX_PAGE_SIZE <= 0) {
    throw new Error('MAX_PAGE_SIZE must be a number > 0.');
  }
  if (!Array.isArray(tokens) || tokens.length === 0) {
    throw new Error('tokens.json must include at least one token entry.');
  }
}

export default function () {
  // なぜ必要か: 反復ごとに同一ユーザー文脈で処理し、データ境界を維持するため。
  const userContext = getUserContext();
  ensureInitialTodos(userContext);

  // なぜ必要か: 要件比率（GET70/POST15/PUT10/DELETE5）を確率分岐で実現するため。
  const roll = Math.random() * 100;
  if (roll < 70) {
    // なぜ必要か: GET内で一覧/詳細の両方を混ぜ、実運用に近い参照負荷を作るため。
    if (Math.random() < 0.7) {
      listTodos(userContext);
    } else {
      const todoId = randomKnownTodoId(userContext);
      if (todoId === null) {
        listTodos(userContext);
      } else {
        getTodo(userContext, todoId);
      }
    }
  } else if (roll < 85) {
    createTodo(userContext, 'regular');
  } else if (roll < 95) {
    // なぜ必要か: IDが空のときは代替で一覧を呼び、反復を無駄にしないため。
    const todoId = randomKnownTodoId(userContext);
    if (todoId === null) {
      listTodos(userContext);
    } else {
      updateTodo(userContext, todoId);
    }
  } else {
    const todoId = randomKnownTodoId(userContext);
    if (todoId === null) {
      listTodos(userContext);
    } else {
      deleteTodo(userContext, todoId);
    }
  }

  // なぜ必要か: 連続リクエストを緩和し、過剰バーストを避けるため。
  sleep(SLEEP_SECONDS);
}
