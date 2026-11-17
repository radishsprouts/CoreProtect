"use strict";

const ACTION_NAMES = {
	0: "ブロック破壊",
	1: "ブロック設置",
	2: "クリック操作",
	3: "キル",
	4: "コンテナ操作",
	6: "チャット",
	7: "コマンド実行",
	8: "ログイン/ログアウト",
	9: "ユーザー名変更",
	10: "インベントリ操作"
};

const PAGE_SIZE = 50;

const lookupForm = document.getElementById( "lookup-form" );
const lookupTableBody = document.querySelector( "#lookup-table tbody" );
const lookupWarningsEl = document.getElementById( "lookup-warnings" );
const lookupCountEl = document.getElementById( "lookup-count" );
const lookupPrevBtn = document.getElementById( "lookup-prev" );
const lookupNextBtn = document.getElementById( "lookup-next" );

const operationForm = document.getElementById( "operation-form" );
const operationTableBody = document.querySelector( "#operation-table tbody" );
const operationWarningsEl = document.getElementById( "operation-warnings" );
const operationSummaryEl = document.getElementById( "operation-preview-summary" );
const operationConfirmEl = document.getElementById( "operation-confirm" );
const operationRollbackBtn = document.getElementById( "operation-rollback" );
const operationRestoreBtn = document.getElementById( "operation-restore" );
const operationJobsEl = document.getElementById( "operation-jobs" );

const netdiffForm = document.getElementById( "netdiff-form" );
const netdiffTableBody = document.querySelector( "#netdiff-table tbody" );
const netdiffWarningsEl = document.getElementById( "netdiff-warnings" );
const netdiffSummaryEl = document.getElementById( "netdiff-summary" );
const netdiffOnlyNegativeEl = document.getElementById( "netdiff-only-negative" );

const purgeTimeEl = document.getElementById( "purge-time" );
const purgeConfirmEl = document.getElementById( "purge-confirm" );
const purgeExecuteBtn = document.getElementById( "purge-execute" );
const maintenanceJobsEl = document.getElementById( "maintenance-jobs" );

const adminSelectEl = document.getElementById( "admin-select" );
const adminHintEl = document.getElementById( "admin-hint" );

const toastEl = document.getElementById( "toast" );

let adminsCache = [];
let appStatus = null;
let previewDone = false;
let lastOperationParams = null;
let lastLookupRows = [];
let lastOperationRows = [];
let lastNetDiffRows = [];
let lookupOffset = 0;
let toastTimer = null;

// ------------------------------------------------------------------
// 共通ヘルパー
// ------------------------------------------------------------------

async function fetchJSON( url, options )
{
	const res = await fetch( url, options );
	let data = null;
	try
	{
		data = await res.json();
	}
	catch ( e )
	{
		data = null;
	}

	if ( !res.ok )
	{
		const message = ( data && data.error ) ? data.error : ( "HTTP " + res.status );
		throw new Error( message );
	}

	return data;
}

function showToast( message, type )
{
	toastEl.textContent = message;
	toastEl.className = "toast show " + ( type || "" );
	clearTimeout( toastTimer );
	toastTimer = setTimeout( () => { toastEl.className = "toast"; }, 3500 );
}

function toQueryString( params )
{
	const usp = new URLSearchParams();
	Object.entries( params ).forEach( ( [key, value] ) =>
	{
		if ( value !== undefined && value !== null && value !== "" )
		{
			usp.set( key, value );
		}
	} );
	return usp.toString();
}

function renderWarnings( el, warnings )
{
	el.textContent = ( warnings && warnings.length > 0 ) ? warnings.join( "\n" ) : "";
}

// ------------------------------------------------------------------
// タブ切り替え
// ------------------------------------------------------------------

document.querySelectorAll( ".tab-button" ).forEach( ( btn ) =>
{
	btn.addEventListener( "click", () =>
	{
		document.querySelectorAll( ".tab-button" ).forEach( ( b ) => b.classList.remove( "active" ) );
		document.querySelectorAll( ".tab-panel" ).forEach( ( p ) => p.classList.remove( "active" ) );
		btn.classList.add( "active" );
		document.getElementById( "tab-" + btn.dataset.tab ).classList.add( "active" );
	} );
} );

// ------------------------------------------------------------------
// アクション種別チェックボックス
// ------------------------------------------------------------------

function buildActionCheckboxes()
{
	document.querySelectorAll( '[data-role="action-checkboxes"]' ).forEach( ( container ) =>
	{
		container.innerHTML = "";
		Object.entries( ACTION_NAMES ).forEach( ( [id, name] ) =>
		{
			const label = document.createElement( "label" );
			const input = document.createElement( "input" );
			input.type = "checkbox";
			input.value = id;
			label.appendChild( input );
			label.appendChild( document.createTextNode( name ) );
			container.appendChild( label );
		} );
	} );
}

// ------------------------------------------------------------------
// 検索条件フォーム
// ------------------------------------------------------------------

function collectFilterForm( formEl )
{
	const fd = new FormData( formEl );
	const actionsContainer = formEl.querySelector( '[data-role="action-checkboxes"]' );
	const actions = actionsContainer
			? Array.from( actionsContainer.querySelectorAll( 'input[type="checkbox"]:checked' ) ).map( ( cb ) => cb.value )
			: [];

	const isGlobal = formEl.querySelector( 'input[name="global"]' ).checked;

	return {
		world: isGlobal ? "" : ( fd.get( "world" ) || "" ),
		x: isGlobal ? "" : ( fd.get( "x" ) || "" ),
		y: isGlobal ? "" : ( fd.get( "y" ) || "" ),
		z: isGlobal ? "" : ( fd.get( "z" ) || "" ),
		radius: fd.get( "radius" ) || "0",
		time: fd.get( "time" ) || "",
		users: fd.get( "users" ) || "",
		excludeUsers: fd.get( "excludeUsers" ) || "",
		blocks: fd.get( "blocks" ) || "",
		excludeBlocks: fd.get( "excludeBlocks" ) || "",
		actions: actions.join( "," )
	};
}

function wireGlobalToggle( formEl )
{
	const checkbox = formEl.querySelector( 'input[name="global"]' );
	const coordFields = formEl.querySelectorAll( ".coord-field" );
	const coordInputs = [ "world", "x", "y", "z", "radius" ]
			.map( ( name ) => formEl.querySelector( `[name="${name}"]` ) );

	const apply = () =>
	{
		const isGlobal = checkbox.checked;
		coordFields.forEach( ( el ) => el.classList.toggle( "range-disabled", isGlobal ) );
		coordInputs.forEach( ( el ) => { el.disabled = isGlobal; } );
	};

	checkbox.addEventListener( "change", apply );
	apply();
}

// ------------------------------------------------------------------
// 結果テーブルの描画
// ------------------------------------------------------------------

function describeTarget( row )
{
	const fragments = [];

	if ( row.signLines && row.signLines.length > 0 )
	{
		const nonBlank = row.signLines.filter( ( l ) => l && l.trim().length > 0 );
		if ( nonBlank.length > 0 )
		{
			fragments.push( "看板: [" + nonBlank.join( " / " ) + "]" );
		}
	}

	if ( row.message )
	{
		fragments.push( row.message );
	}
	else if ( row.actionId === 6 || row.actionId === 7 )
	{
		const raw = row.raw || [];
		fragments.push( raw.length > 0 ? raw[raw.length - 1] : ( row.type || "-" ) );
	}
	else if ( row.actionId === 8 )
	{
		if ( row.action === "login" ) fragments.push( "ログイン" );
		else if ( row.action === "logout" ) fragments.push( "ログアウト" );
		else fragments.push( row.type || "-" );
	}
	else if ( row.containerAmount !== undefined && row.containerAmount !== null )
	{
		fragments.push( ( row.type || "-" ) + " ×" + row.containerAmount );
	}
	else
	{
		fragments.push( row.type || "-" );
	}

	if ( row.blockData )
	{
		fragments.push( "[" + row.blockData + "]" );
	}
	if ( row.entityDetail )
	{
		fragments.push( "(" + row.entityDetail + ")" );
	}

	return fragments.join( " " );
}

function describeAction( row )
{
	if ( row.containerDirection !== undefined )
	{
		return row.containerDirection === "in" ? "コンテナ収納" : "コンテナ取り出し";
	}

	if ( row.actionId === 8 )
	{
		if ( row.action === "login" ) return "ログイン";
		if ( row.action === "logout" ) return "ログアウト";
		return "ログイン/ログアウト";
	}

	return ACTION_NAMES[row.actionId] !== undefined ? ACTION_NAMES[row.actionId] : ( row.action || ( "#" + row.actionId ) );
}

function buildCoordButton( world, x, y, z )
{
	const btn = document.createElement( "button" );
	btn.type = "button";
	btn.className = "coord-link";
	btn.textContent = ( world || "-" ) + " (" + Math.floor( x ) + ", " + Math.floor( y ) + ", " + Math.floor( z ) + ")";

	const admin = getSelectedAdmin();
	if ( !admin )
	{
		btn.disabled = true;
		btn.title = "管理者が選択されていません";
	}
	else if ( !admin.online )
	{
		btn.disabled = true;
		btn.title = "選択中の管理者はオフラインです";
	}
	else
	{
		btn.title = "クリックでこの座標へテレポート";
		btn.addEventListener( "click", () => teleportTo( world, x, y, z ) );
	}

	return btn;
}

function renderResultTable( tbody, rows )
{
	tbody.innerHTML = "";

	if ( !rows || rows.length === 0 )
	{
		const tr = document.createElement( "tr" );
		const td = document.createElement( "td" );
		td.colSpan = 6;
		td.textContent = "該当する記録がありません。";
		td.className = "hint";
		tr.appendChild( td );
		tbody.appendChild( tr );
		return;
	}

	rows.forEach( ( row ) =>
	{
		const tr = document.createElement( "tr" );

		const timeTd = document.createElement( "td" );
		timeTd.textContent = row.timestamp ? new Date( row.timestamp * 1000 ).toLocaleString( "ja-JP" ) : "-";
		tr.appendChild( timeTd );

		const playerTd = document.createElement( "td" );
		playerTd.textContent = row.player || "-";
		tr.appendChild( playerTd );

		const actionTd = document.createElement( "td" );
		actionTd.textContent = describeAction( row );
		tr.appendChild( actionTd );

		const targetTd = document.createElement( "td" );
		targetTd.textContent = describeTarget( row );
		tr.appendChild( targetTd );

		const coordTd = document.createElement( "td" );
		coordTd.appendChild( buildCoordButton( row.world, row.x, row.y, row.z ) );
		tr.appendChild( coordTd );

		const rolledTd = document.createElement( "td" );
		rolledTd.textContent = row.rolledBack ? "済" : "";
		tr.appendChild( rolledTd );

		tbody.appendChild( tr );
	} );
}

// ------------------------------------------------------------------
// 調査タブ
// ------------------------------------------------------------------

async function doLookup( offset )
{
	const filter = collectFilterForm( lookupForm );
	const params = Object.assign( {}, filter, { offset: offset, rowCount: PAGE_SIZE } );

	try
	{
		const data = await fetchJSON( "/api/lookup?" + toQueryString( params ) );
		renderWarnings( lookupWarningsEl, data.warnings );
		lastLookupRows = data.rows || [];
		renderResultTable( lookupTableBody, lastLookupRows );
		lookupCountEl.textContent = ( data.count || 0 ) + " 件表示中（オフセット " + offset + "）";
		lookupPrevBtn.disabled = offset <= 0;
		lookupNextBtn.disabled = ( data.count || 0 ) < PAGE_SIZE;
		lookupOffset = offset;
	}
	catch ( e )
	{
		showToast( e.message, "error" );
	}
}

lookupForm.addEventListener( "submit", ( ev ) =>
{
	ev.preventDefault();
	doLookup( 0 );
} );

lookupPrevBtn.addEventListener( "click", () => doLookup( Math.max( 0, lookupOffset - PAGE_SIZE ) ) );
lookupNextBtn.addEventListener( "click", () => doLookup( lookupOffset + PAGE_SIZE ) );

// ------------------------------------------------------------------
// ロールバック・リストアタブ
// ------------------------------------------------------------------

function updateConfirmButtons()
{
	const canRun = previewDone && operationConfirmEl.checked;
	const ops = ( appStatus && appStatus.operations ) || {};
	operationRollbackBtn.disabled = !canRun || !ops.rollbackEnabled;
	operationRestoreBtn.disabled = !canRun || !ops.restoreEnabled;
	purgeExecuteBtn.disabled = !purgeConfirmEl.checked || !ops.purgeEnabled;
}

async function doPreview( ev )
{
	if ( ev ) ev.preventDefault();
	const filter = collectFilterForm( operationForm );
	const params = Object.assign( {}, filter, { offset: 0, rowCount: PAGE_SIZE } );

	try
	{
		const data = await fetchJSON( "/api/lookup?" + toQueryString( params ) );
		renderWarnings( operationWarningsEl, data.warnings );
		lastOperationRows = data.rows || [];
		renderResultTable( operationTableBody, lastOperationRows );
		operationSummaryEl.textContent = "プレビュー: " + ( data.count || 0 ) +
				" 件表示（最大" + PAGE_SIZE + "件まで表示。実際の対象件数は実行時に確定します）";
		lastOperationParams = filter;
		previewDone = true;
	}
	catch ( e )
	{
		showToast( e.message, "error" );
		previewDone = false;
	}

	updateConfirmButtons();
}

function toOperationBody( filter )
{
	const body = Object.assign( {}, filter, { confirm: true } );
	body.radius = Number( body.radius ) || 0;

	if ( body.x !== "" ) { body.x = Number( body.x ); } else { delete body.x; }
	if ( body.y !== "" ) { body.y = Number( body.y ); } else { delete body.y; }
	if ( body.z !== "" ) { body.z = Number( body.z ); } else { delete body.z; }

	body.actions = body.actions ? body.actions.split( "," ).map( Number ) : [];
	body.users = splitCsv( body.users );
	body.excludeUsers = splitCsv( body.excludeUsers );
	body.blocks = splitCsv( body.blocks );
	body.excludeBlocks = splitCsv( body.excludeBlocks );

	return body;
}

function splitCsv( raw )
{
	return raw ? raw.split( "," ).map( ( s ) => s.trim() ).filter( ( s ) => s.length > 0 ) : [];
}

async function submitOperation( type )
{
	if ( !lastOperationParams )
	{
		showToast( "先にプレビューを実行してください", "error" );
		return;
	}

	const body = toOperationBody( lastOperationParams );
	const label = type === "rollback" ? "ロールバック" : "リストア";

	try
	{
		const res = await fetchJSON( "/api/" + type, {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify( body )
		} );

		showToast( label + "を開始しました", "success" );
		trackJob( res.jobId, operationJobsEl, label );
		previewDone = false;
		updateConfirmButtons();
	}
	catch ( e )
	{
		showToast( e.message, "error" );
	}
}

operationForm.addEventListener( "submit", doPreview );
operationConfirmEl.addEventListener( "change", updateConfirmButtons );
operationRollbackBtn.addEventListener( "click", () => submitOperation( "rollback" ) );
operationRestoreBtn.addEventListener( "click", () => submitOperation( "restore" ) );

// ------------------------------------------------------------------
// 持ち出し集計タブ
// ------------------------------------------------------------------

netdiffForm.addEventListener( "submit", async ( ev ) =>
{
	ev.preventDefault();
	const filter = collectFilterForm( netdiffForm );
	const params = Object.assign( {}, filter, {
		includeNonPlayer: "false"
	} );

	try
	{
		const data = await fetchJSON( "/api/container-net?" + toQueryString( params ) );
		renderWarnings( netdiffWarningsEl, data.warnings );
		lastNetDiffRows = data.rows || [];
		renderNetDiffTable( lastNetDiffRows );

		const scanned = data.scannedRows || 0;
		const truncatedMsg = data.truncated ? "（上限に達したため打ち切り）" : "";
		netdiffSummaryEl.textContent = "走査行数: " + scanned + " 件" + truncatedMsg + "、集計結果: " + lastNetDiffRows.length + " 件";
	}
	catch ( e )
	{
		showToast( e.message, "error" );
	}
} );

netdiffOnlyNegativeEl.addEventListener( "change", () => renderNetDiffTable( lastNetDiffRows ) );

function renderNetDiffTable( rows )
{
	netdiffTableBody.innerHTML = "";
	const onlyNeg = netdiffOnlyNegativeEl.checked;
	const filtered = rows.filter( ( r ) => ( !onlyNeg || r.net < 0 ) );

	if ( filtered.length === 0 )
	{
		const tr = document.createElement( "tr" );
		const td = document.createElement( "td" );
		td.colSpan = 8;
		td.textContent = "該当する集計結果がありません。";
		td.className = "hint";
		tr.appendChild( td );
		netdiffTableBody.appendChild( tr );
		return;
	}

	filtered.forEach( ( row ) =>
	{
		const tr = document.createElement( "tr" );

		const playerTd = document.createElement( "td" );
		playerTd.textContent = row.player;
		tr.appendChild( playerTd );

		const itemTd = document.createElement( "td" );
		itemTd.textContent = row.item;
		tr.appendChild( itemTd );

		const addTd = document.createElement( "td" );
		addTd.textContent = row.added;
		tr.appendChild( addTd );

		const remTd = document.createElement( "td" );
		remTd.textContent = row.removed;
		tr.appendChild( remTd );

		const netTd = document.createElement( "td" );
		netTd.textContent = ( row.net > 0 ? "+" : "" ) + row.net;
		netTd.className = row.net < 0 ? "net-negative" : ( row.net > 0 ? "net-positive" : "" );
		tr.appendChild( netTd );

		const countTd = document.createElement( "td" );
		countTd.textContent = row.txCount;
		tr.appendChild( countTd );

		const firstTd = document.createElement( "td" );
		firstTd.textContent = row.firstSeen ? new Date( row.firstSeen * 1000 ).toLocaleString( "ja-JP" ) : "-";
		tr.appendChild( firstTd );

		const lastTd = document.createElement( "td" );
		lastTd.textContent = row.lastSeen ? new Date( row.lastSeen * 1000 ).toLocaleString( "ja-JP" ) : "-";
		tr.appendChild( lastTd );

		netdiffTableBody.appendChild( tr );
	} );
}

// ------------------------------------------------------------------
// メンテナンスタブ (パージ)
// ------------------------------------------------------------------

purgeConfirmEl.addEventListener( "change", updateConfirmButtons );

purgeExecuteBtn.addEventListener( "click", async () =>
{
	const time = purgeTimeEl.value.trim();
	if ( !time )
	{
		showToast( "期間を指定してください", "error" );
		return;
	}

	try
	{
		const res = await fetchJSON( "/api/purge", {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify( { confirm: true, time: time } )
		} );

		showToast( "パージジョブを開始しました", "success" );
		trackJob( res.jobId, maintenanceJobsEl, "パージ" );
		purgeConfirmEl.checked = false;
		updateConfirmButtons();
	}
	catch ( e )
	{
		showToast( e.message, "error" );
	}
} );

// ------------------------------------------------------------------
// ジョブ監視
// ------------------------------------------------------------------

function trackJob( jobId, containerEl, label )
{
	const item = document.createElement( "div" );
	item.className = "job-item";
	item.innerHTML = "<span>" + label + " (" + jobId.substring( 0, 8 ) + ")</span><span class='job-status RUNNING'>実行中...</span>";
	containerEl.prepend( item );

	const statusSpan = item.querySelector( ".job-status" );
	const timer = setInterval( async () =>
	{
		try
		{
			const job = await fetchJSON( "/api/jobs/" + jobId );
			statusSpan.className = "job-status " + job.status;
			if ( job.status === "DONE" )
			{
				clearInterval( timer );
				statusSpan.textContent = "完了 (対象 " + ( job.affectedRows >= 0 ? job.affectedRows : 0 ) + " 件)";
			}
			else if ( job.status === "ERROR" )
			{
				clearInterval( timer );
				statusSpan.textContent = "失敗: " + ( job.errorMessage || "不明なエラー" );
			}
		}
		catch ( e )
		{
			clearInterval( timer );
			statusSpan.className = "job-status ERROR";
			statusSpan.textContent = "状態取得失敗";
		}
	}, 1500 );
}

// ------------------------------------------------------------------
// 管理者ピッカー / テレポート
// ------------------------------------------------------------------

async function loadAdmins()
{
	try
	{
		const data = await fetchJSON( "/api/admins" );
		adminsCache = data.admins || [];
		adminSelectEl.innerHTML = '<option value="">（未選択）</option>';
		adminsCache.forEach( ( a ) =>
		{
			const opt = document.createElement( "option" );
			opt.value = a.uuid;
			opt.textContent = a.name + ( a.online ? " [オンライン]" : " [オフライン]" );
			adminSelectEl.appendChild( opt );
		} );
		updateAdminHint();
		reRenderCoordButtons();
	}
	catch ( e )
	{
		adminHintEl.textContent = "管理者取得失敗";
	}
}

function getSelectedAdmin()
{
	const uuid = adminSelectEl.value;
	return adminsCache.find( ( a ) => a.uuid === uuid );
}

function updateAdminHint()
{
	const admin = getSelectedAdmin();
	if ( !admin )
	{
		adminHintEl.textContent = "";
	}
	else if ( !admin.online )
	{
		adminHintEl.textContent = "※オフラインのためテレポート不可";
	}
	else
	{
		adminHintEl.textContent = "現在地: " + admin.world + " (" + Math.floor( admin.x ) + ", " + Math.floor( admin.y ) + ", " + Math.floor( admin.z ) + ")";
	}
}

adminSelectEl.addEventListener( "change", () =>
{
	updateAdminHint();
	reRenderCoordButtons();
} );

document.getElementById( "admin-refresh" ).addEventListener( "click", loadAdmins );

document.getElementById( "admin-goto" ).addEventListener( "click", () =>
{
	const admin = getSelectedAdmin();
	if ( !admin )
	{
		showToast( "管理者が選択されていません", "error" );
		return;
	}
	if ( !admin.online )
	{
		showToast( "選択中の管理者はオフラインです", "error" );
		return;
	}

	[lookupForm, operationForm, netdiffForm].forEach( ( form ) =>
	{
		const globalCheckbox = form.querySelector( 'input[name="global"]' );
		if ( globalCheckbox.checked )
		{
			globalCheckbox.checked = false;
			globalCheckbox.dispatchEvent( new Event( "change" ) );
		}

		form.querySelector( '[name="world"]' ).value = admin.world;
		form.querySelector( '[name="x"]' ).value = Math.floor( admin.x );
		form.querySelector( '[name="y"]' ).value = Math.floor( admin.y );
		form.querySelector( '[name="z"]' ).value = Math.floor( admin.z );
	} );

	showToast( "検索条件に現在地を反映しました", "success" );
} );

async function teleportTo( world, x, y, z )
{
	const admin = getSelectedAdmin();
	if ( !admin )
	{
		showToast( "管理者が選択されていません", "error" );
		return;
	}

	try
	{
		await fetchJSON( "/api/teleport", {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify( { adminId: admin.uuid, world: world, x: x, y: y, z: z } )
		} );
		showToast( "テレポートしました", "success" );
	}
	catch ( e )
	{
		showToast( e.message, "error" );
	}
}

function reRenderCoordButtons()
{
	renderResultTable( lookupTableBody, lastLookupRows );
	renderResultTable( operationTableBody, lastOperationRows );
}

// ------------------------------------------------------------------
// ステータスタブ / ワールド一覧
// ------------------------------------------------------------------

function populateWorldSelects( worlds )
{
	document.querySelectorAll( ".world-select" ).forEach( ( sel ) =>
	{
		const current = sel.value;
		sel.innerHTML = "";
		worlds.forEach( ( w ) =>
		{
			const opt = document.createElement( "option" );
			opt.value = w;
			opt.textContent = w;
			sel.appendChild( opt );
		} );
		if ( worlds.includes( current ) )
		{
			sel.value = current;
		}
	} );
}

function statusCard( title, value, valueClass, listItems )
{
	const card = document.createElement( "div" );
	card.className = "status-card";

	const h3 = document.createElement( "h3" );
	h3.textContent = title;
	card.appendChild( h3 );

	if ( value )
	{
		const v = document.createElement( "div" );
		v.className = "value" + ( valueClass ? " " + valueClass : "" );
		v.textContent = value;
		card.appendChild( v );
	}

	if ( listItems && listItems.length > 0 )
	{
		const ul = document.createElement( "ul" );
		listItems.forEach( ( text ) =>
		{
			const li = document.createElement( "li" );
			li.textContent = text;
			ul.appendChild( li );
		} );
		card.appendChild( ul );
	}

	return card;
}

function renderStatus( status )
{
	const el = document.getElementById( "status-content" );
	el.innerHTML = "";

	el.appendChild( statusCard(
			"CoreProtect",
			status.coreProtectAvailable ? "稼働中" : "未検出",
			status.coreProtectAvailable ? "ok" : "ng",
			status.coreProtectApiVersion !== undefined ? ["APIバージョン: " + status.coreProtectApiVersion] : [] ) );

	el.appendChild( statusCard(
			"データベース",
			status.databaseEngine || "Unknown",
			"ok",
			["未書き込みキュー: " + ( status.pendingQueueSize !== undefined ? status.pendingQueueSize : 0 ) + " 件"] ) );

	el.appendChild( statusCard( "サーバー環境", status.folia ? "Folia" : "Paper/Spigot", "ok", [] ) );

	el.appendChild( statusCard( "ワールド", String( ( status.worlds || [] ).length ) + " 個", "", status.worlds || [] ) );

	el.appendChild( statusCard( "オンラインプレイヤー", String( ( status.onlinePlayers || [] ).length ) + " 人", "", status.onlinePlayers || [] ) );

	const ops = status.operations || {};
	el.appendChild( statusCard( "操作の許可状態", "", "", [
		"ロールバック: " + ( ops.rollbackEnabled ? "許可" : "禁止" ),
		"リストア: " + ( ops.restoreEnabled ? "許可" : "禁止" ),
		"パージ: " + ( ops.purgeEnabled ? "許可" : "禁止" ),
		"テレポート: " + ( ops.teleportEnabled ? "許可" : "禁止" )
	] ) );

	const jobs = status.jobs || [];
	el.appendChild( statusCard( "実行中/直近のジョブ", String( jobs.length ) + " 件",
			"", jobs.map( ( j ) => j.type + ": " + j.status ) ) );
}

function renderStatusError( message )
{
	const el = document.getElementById( "status-content" );
	el.innerHTML = "";
	el.appendChild( statusCard( "ステータスを取得できませんでした", "", "ng", [message, "上の「更新」ボタンで再取得できます。"] ) );
}

async function loadStatusAndWorlds()
{
	try
	{
		appStatus = await fetchJSON( "/api/status" );
		populateWorldSelects( appStatus.worlds || [] );
		renderStatus( appStatus );
		updateConfirmButtons();
	}
	catch ( e )
	{
		appStatus = null;
		renderStatusError( e.message );
		updateConfirmButtons();
		showToast( "ステータス取得に失敗しました: " + e.message, "error" );
	}
}

// ------------------------------------------------------------------
// 初期化
// ------------------------------------------------------------------

document.getElementById( "status-refresh" ).addEventListener( "click", loadStatusAndWorlds );

buildActionCheckboxes();
wireGlobalToggle( lookupForm );
wireGlobalToggle( operationForm );
wireGlobalToggle( netdiffForm );
loadStatusAndWorlds();
loadAdmins();
updateConfirmButtons();
