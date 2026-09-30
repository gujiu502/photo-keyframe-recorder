package com.eva.recordings.domain.exceptions

class CannotTrashFileDifferentOwnerException :
	Exception("無法將其他應用程式的檔案移至回收桶")