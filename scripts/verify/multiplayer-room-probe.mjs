// End-to-end probe of a running OSADA room server.
//
//   node scripts/verify/multiplayer-room-probe.mjs ws://<host>/mp
//   node scripts/verify/multiplayer-room-probe.mjs            # uses OSADA_MP_ENDPOINT, else localhost
//
// Drives two sockets through the whole lobby-to-command path the game uses: create, join, ready,
// start, a guest proposal reaching the host, the host's commit reaching the guest, a stale
// proposal being refused, and a dropped guest taking its seat back with the stored snapshot.
// It speaks the wire protocol directly, so it verifies the deployed server rather than the client.
import WebSocket from 'ws';

const ENDPOINT = process.argv[2] || process.env.OSADA_MP_ENDPOINT || 'ws://127.0.0.1:8090/mp';
// The server may pin an Origin allowlist (OSADA_ALLOWED_ORIGINS), and a browser always sends one,
// so the probe presents the origin a real player's page would have.
const ORIGIN = process.argv[3] || ENDPOINT.replace(/^ws/, 'http').replace(/\/mp$/, '');
const PROTOCOL_VERSION = 1;
const TIMEOUT_MS = 20000;

const failures = [];

function check(condition, description) {
    if (condition) {
        console.log(`  ok   ${description}`);
    } else {
        console.log(`  FAIL ${description}`);
        failures.push(description);
    }
}

class Client {
    constructor(name, origin = ORIGIN) {
        this.name = name;
        this.origin = origin;
        this.queue = [];
        this.waiters = [];
    }

    async open() {
        this.socket = new WebSocket(ENDPOINT, { origin: this.origin });
        this.socket.on('message', (raw) => {
            const message = JSON.parse(raw.toString());
            const waiter = this.waiters.shift();
            if (waiter) waiter(message);
            else this.queue.push(message);
        });
        await new Promise((resolve, reject) => {
            this.socket.once('open', resolve);
            this.socket.once('error', reject);
        });
        return this;
    }

    send(type, payload = {}) {
        this.socket.send(JSON.stringify({
            protocolVersion: PROTOCOL_VERSION,
            type,
            sentAt: Date.now(),
            payload,
        }));
    }

    // Reads until a message of one of `types` arrives, so unrelated broadcasts cannot
    // desynchronize the probe.
    async await(...types) {
        const deadline = Date.now() + TIMEOUT_MS;
        for (;;) {
            const queued = this.queue.findIndex((message) => types.includes(message.type));
            if (queued >= 0) return this.queue.splice(queued, 1)[0];
            if (Date.now() > deadline) {
                throw new Error(`${this.name}: timed out waiting for ${types.join('/')}`);
            }
            const message = await new Promise((resolve, reject) => {
                const timer = setTimeout(() => reject(new Error(`${this.name}: timeout`)), TIMEOUT_MS);
                this.waiters.push((value) => {
                    clearTimeout(timer);
                    resolve(value);
                });
            });
            if (types.includes(message.type)) return message;
            this.queue.push(message);
        }
    }

    close() {
        this.socket?.close();
    }
}

async function awaitLobby(client, predicate) {
    for (;;) {
        const lobby = await client.await('LOBBY_STATE');
        if (predicate(lobby.payload.participants)) return lobby.payload;
    }
}

const snapshotOf = (revision) => ({
    snapshotFormatVersion: 1,
    gameSaveFormatVersion: 2,
    protocolVersion: 1,
    gameVersion: 'probe',
    contentManifestHash: 'probe',
    roomConfigHash: 'probe',
    authorityEpoch: 0,
    revision,
    createdAt: Date.now(),
    gameState: { fmt: 2 },
    multiplayerState: {
        status: 'RUNNING',
        revision,
        authorityParticipantId: 'probe',
        authorityEpoch: 0,
        readyParticipantIds: [],
        sharedPrestigeAccounts: {},
        unitAssignments: {},
        pendingCommand: null,
    },
    stateHash: `sha256:probe-${revision}`,
});

async function main() {
    console.log(`OSADA room probe against ${ENDPOINT} (origin ${ORIGIN})`);

    // Is the Origin allowlist actually enforced? A page on another site must not be able to open
    // rooms here. Reported rather than failed, because a local dev server legitimately runs open.
    let strangerAccepted = false;
    try {
        const stranger = await new Client('stranger', 'https://evil.example').open();
        stranger.send('CREATE_ROOM', { displayName: 'Stranger' });
        const answer = await stranger.await('WELCOME', 'ROOM_ERROR');
        strangerAccepted = answer.type === 'WELCOME';
        stranger.close();
    } catch {
        strangerAccepted = false;
    }
    console.log(strangerAccepted
        ? '  note  Origin allowlist is OPEN (OSADA_ALLOWED_ORIGINS empty) — any site may open rooms'
        : '  ok    a socket from a foreign Origin is refused');

    const host = await new Client('host').open();
    host.send('CREATE_ROOM', { displayName: 'Probe Host' });
    const welcome = await host.await('WELCOME', 'ROOM_ERROR');
    check(welcome.type === 'WELCOME', 'the server accepts CREATE_ROOM');
    const roomCode = welcome.payload.roomCode;
    check(/^[A-Z2-9]{6}$/.test(roomCode || ''), `room code looks right (${roomCode})`);
    check(welcome.payload.isHost === true, 'the creator is the host');
    check(typeof welcome.payload.reconnectToken === 'string', 'a reconnect token is issued');

    const guest = await new Client('guest').open();
    guest.send('JOIN_ROOM', { roomCode, displayName: 'Probe Guest' });
    const guestWelcome = await guest.await('WELCOME', 'ROOM_ERROR');
    check(guestWelcome.type === 'WELCOME', 'a second commander can join by code');
    const guestToken = guestWelcome.payload.reconnectToken;

    const lobby = await awaitLobby(host, (participants) => participants.length === 2);
    check(lobby.participants[0].displayName === 'Probe Host', 'the roster carries display names');
    check(lobby.participants[1].isHost === false, 'only one participant is host');

    const third = await new Client('third').open();
    third.send('JOIN_ROOM', { roomCode, displayName: 'Third' });
    const refused = await third.await('ROOM_ERROR', 'WELCOME');
    check(refused.payload?.code === 'ROOM_FULL', 'a third commander is refused with ROOM_FULL');
    third.close();

    guest.send('START_GAME_PROPOSE', { scenarioFile: 'bn9s00.xml' });
    const notHost = await guest.await('ROOM_ERROR', 'START_GAME');
    check(notHost.payload?.code === 'NOT_ROOM_HOST', 'a guest cannot start the match');

    // The host picks the battle; the guest must see the same choice before anyone readies up,
    // because choosing clears readiness.
    host.send('LOBBY_PATCH_PROPOSE', { scenarioFile: 'bn9s00.xml' });
    let guestScenario = null;
    while (guestScenario === null) {
        guestScenario = (await guest.await('LOBBY_STATE')).payload.scenarioFile ?? null;
    }
    check(guestScenario === 'bn9s00.xml', "the host's scenario choice reaches the guest");
    while (((await host.await('LOBBY_STATE')).payload.scenarioFile ?? null) === null) { /* settle */ }

    host.send('SET_READY', { ready: true });
    guest.send('SET_READY', { ready: true });
    await awaitLobby(host, (participants) =>
        participants.length === 2 && participants.every((participant) => participant.isReady));
    // No scenarioFile here: the server must start on what the lobby agreed.
    host.send('START_GAME_PROPOSE', {});
    const started = await host.await('START_GAME', 'ROOM_ERROR');
    check(started.type === 'START_GAME', 'the host starts the match');
    check(started.payload.scenarioFile === 'bn9s00.xml', 'the match starts on the chosen scenario');
    await guest.await('START_GAME');

    host.send('SNAPSHOT', snapshotOf(0));
    await guest.await('SNAPSHOT');
    check(true, 'the initial snapshot reaches the guest');

    guest.send('COMMAND_PROPOSE', {
        clientMessageId: 'probe-1',
        expectedRevision: 0,
        command: { kind: 'MoveUnit', unitId: 1, actorPlayerId: 0 },
    });
    const forAuthority = await host.await('COMMAND_FOR_AUTHORITY', 'COMMAND_REJECT');
    check(forAuthority.type === 'COMMAND_FOR_AUTHORITY', "a guest order reaches the host");
    check(forAuthority.payload.clientMessageId === 'probe-1', 'the order keeps its message id');
    check(forAuthority.payload.command?.kind === 'MoveUnit', 'the order keeps its payload');

    host.send('COMMAND_COMMIT', snapshotOf(1));
    const commit = await guest.await('COMMAND_COMMIT');
    check(commit.payload.revision === 1, 'the committed snapshot reaches the guest at revision 1');

    guest.send('COMMAND_PROPOSE', {
        clientMessageId: 'probe-stale',
        expectedRevision: 0,
        command: { kind: 'MoveUnit', unitId: 2, actorPlayerId: 0 },
    });
    const stale = await guest.await('COMMAND_REJECT');
    check(stale.payload.code === 'STALE_STATE', 'an order against an old revision is refused');

    guest.close();
    const returning = await new Client('returning guest').open();
    returning.send('JOIN_ROOM', { roomCode, displayName: 'Probe Guest', reconnectToken: guestToken });
    const back = await returning.await('WELCOME', 'ROOM_ERROR');
    check(back.type === 'WELCOME', 'a returning guest is let back in');
    check(back.payload.started === true, 'it learns the match is already running');
    check(back.payload.revision === 1, 'it learns the current revision');
    const resent = await returning.await('SNAPSHOT');
    check(resent.payload.revision === 1, 'it receives the stored snapshot');

    returning.send('LEAVE_ROOM', {});
    host.send('LEAVE_ROOM', {});
    returning.close();
    host.close();
}

main()
    .then(() => {
        console.log(failures.length === 0
            ? '\nOverall: PASS'
            : `\nOverall: FAIL (${failures.length})`);
        process.exit(failures.length === 0 ? 0 : 1);
    })
    .catch((error) => {
        console.error(`\nprobe error: ${error.message}`);
        process.exit(1);
    });
