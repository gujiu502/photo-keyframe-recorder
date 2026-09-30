package com.eva.location.domain.exceptions

class CannotFoundLastLocationException :
	Exception("沒有上次位置資訊，請取得目前位置")