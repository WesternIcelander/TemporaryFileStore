(function () {
    let timezone;
    let uploadInfoDiv;
    const xhrPromise = function (xhr) {
        return new Promise((resolve, reject) => {
            xhr.onreadystatechange = function (e) {
                if (xhr.readyState == XMLHttpRequest.DONE) {
                    resolve(xhr.responseText);
                }
            };
        });
    };
    const formatLink = function (fileId, link) {
        if (!link.endsWith(fileId)) {
            return link;
        }
        return link.substring(0, link.length - fileId.length) + "<div class=\"fileid\">" + fileId + "</div>";
    };
    const uploadFile = async function (file) {
        let listItem = addFileItem(file.name);

        let fileUrl = null;
        let fileId = null;
        let uploadXhr = null;

        let preuploadXhr = new XMLHttpRequest();
        preuploadXhr.open("POST", "/preupload");
        let preuploadXhrPromise = xhrPromise(preuploadXhr);
        preuploadXhr.setRequestHeader("Content-Type", "application/x-www-form-urlencoded");
        preuploadXhr.send("size=" + file.size);
        let preuploadResult = JSON.parse(await preuploadXhrPromise);
        if (!preuploadResult.success) {
            if (preuploadResult.errorMessage) {
                listItem.setStatus(preuploadResult.errorMessage);
            } else {
                listItem.setStatus("Upload failed");
            }
            return;
        }

        fileUrl = preuploadResult.link;
        fileId = preuploadResult.fileId;
        listItem.setLink(preuploadResult.fileId, preuploadResult.link);

        listItem.addButton("Copy Link", (e) => {
            e.preventDefault();
            navigator.clipboard.writeText(fileUrl);
        });
        let downloadButton = listItem.addButton("Download", fileUrl);
        let deleteButton = listItem.addButton("Cancel Upload", async (e) => {
            e.preventDefault();
            if (uploadXhr && uploadXhr.readystate != XMLHttpRequest.DONE) {
                uploadXhr.abort();
            }
            if (!(await deleteFile(fileId))) {
                listItem.setStatus("Unable to delete");
                return;
            }
            listItem.setLink(null, null);
            listItem.setStatus("(deleted)");
            listItem.removeAllButtons();
            listItem.addButton("Hide", (e) => {
                e.preventDefault();
                listItem.removeFromList();
            });
        });

        let formData = new FormData();
        formData.append("json", "1");
        formData.append("timezone", timezone);
        formData.append("fileId", preuploadResult.fileId);
        formData.append("file", file);
        uploadXhr = new XMLHttpRequest();
        uploadXhr.open("POST", "/upload");
        let uploadPromise = xhrPromise(uploadXhr);
        uploadXhr.upload.onprogress = function (e) {
            if (!e.lengthComputable) {
                return;
            }
            let progress = Math.floor((e.loaded / e.total) * 100);
            listItem.setStatus("Uploading: " + progress + "%");
        }
        uploadXhr.send(formData);
        let uploadResult = JSON.parse(await uploadPromise);
        if (uploadResult.link) {
            fileUrl = uploadResult.link;
        }
        if (uploadXhr.status == 200) {
            if (uploadResult.expiry) {
                listItem.setStatus("Expires: " + uploadResult.expiry);
            } else {
                listItem.setStatus("Done!");
            }
            downloadButton.href = uploadResult.link;
            deleteButton.innerText = "Delete";
        } else {
            uploadProgressDiv.innerText = "Upload failed";
            listItem.removeAllButtons();
        }
    };
    const deleteFile = async function (fileId) {
        let xhr = new XMLHttpRequest();
        xhr.open("POST", "/delete");
        let fetchPromise = xhrPromise(xhr);
        xhr.setRequestHeader("Content-Type", "application/x-www-form-urlencoded");
        xhr.send("fileId=" + fileId);

        let result = JSON.parse(await fetchPromise);
        return result.success;
    }
    const fetchPreviousUploads = async function () {
        let xhr = new XMLHttpRequest();
        xhr.open("POST", "/uploads");
        let fetchPromise = xhrPromise(xhr);
        xhr.setRequestHeader("Content-Type", "application/x-www-form-urlencoded");
        xhr.send("timezone=" + timezone);

        let result = JSON.parse(await fetchPromise);
        if (result.length == 0) return;
        uploadInfoDiv.style.display = "block";

        for (let i = 0; i < result.length; i++) {
            let listItem = addFileItem(result[i].file);
            let fileInfo = result[i];
            listItem.setLink(fileInfo.fileId, fileInfo.link);
            listItem.setStatus("Expires: " + fileInfo.expiry);
            listItem.addButton("Copy Link", (e) => {
                e.preventDefault();
                navigator.clipboard.writeText(fileInfo.link);
            });
            listItem.addButton("Download", fileInfo.link);
            listItem.addButton("Delete", async (e) => {
                e.preventDefault();
                if (!(await deleteFile(fileInfo.fileId))) {
                    listItem.setStatus("Unable to delete");
                    return;
                }
                listItem.setLink(null, null);
                listItem.setStatus("(deleted)");
                listItem.removeAllButtons();
                listItem.addButton("Hide", () => {
                    e.preventDefault();
                    listItem.removeFromList();
                })
            });
        }
    }
    const addFileItem = function (fileName) {
        uploadInfoDiv.style.display = "block";

        const createDiv = function (className) {
            let div = document.createElement("DIV");
            div.className = className;
            return div;
        }

        let uploadInfoBlock = createDiv("uploadinfo");
        if (uploadInfoDiv.firstChild) {
            uploadInfoDiv.insertBefore(uploadInfoBlock, uploadInfoDiv.firstChild);
        } else {
            uploadInfoDiv.appendChild(uploadInfoBlock);
        }
        let fileNameDiv = createDiv("uploadinfoline");
        uploadInfoBlock.appendChild(fileNameDiv);
        let fileLinkDiv = createDiv("uploadinfoline");
        uploadInfoBlock.appendChild(fileLinkDiv);
        let fileLink = document.createElement("A");
        fileLink.target = "_blank";
        fileLink.className = "filelink";
        fileLinkDiv.appendChild(fileLink);
        let statusDiv = createDiv("uploadinfoline");
        uploadInfoBlock.appendChild(statusDiv);
        let actionsDiv = createDiv("uploadinfoline");
        uploadInfoBlock.appendChild(actionsDiv);

        fileNameDiv.innerText = fileName;

        const setLink = function (fileId, link) {
            if (!fileId || !link) {
                fileLink.innerHTML = "";
                fileLink.href = "";
            } else {
                fileLink.innerHTML = formatLink(fileId, link);
                fileLink.href = link;
            }
        };
        const setStatus = function (text) {
            statusDiv.innerText = text;
        };
        const addButton = function (text, action) {
            let button = document.createElement("A");
            button.className = "actionbutton";
            button.innerText = text;
            if (action) {
                if (typeof action === "string") {
                    button.href = action;
                    button.target = "_blank";
                } else {
                    button.href = "#";
                    button.addEventListener("click", action);
                }
            }
            actionsDiv.appendChild(button);
            return button;
        };
        const removeButton = function (button) {
            if (typeof button === "string") {
                let buttons = actionsDiv.getElementsByTagName("A");
                for (let i = 0; i < buttons.length; i++) {
                    if (buttons[i].innerText === button) {
                        buttons[i].parentNode.removeChild(buttons[i]);
                        break;
                    }
                }
            } else {
                button.parentNode.removeChild(button);
            }
        };
        const removeAllButtons = function () {
            while (actionsDiv.firstChild) {
                actionsDiv.removeChild(actionsDiv.firstChild);
            }
        };
        const removeFromList = function () {
            uploadInfoDiv.removeChild(uploadInfoBlock);
        };

        return {setLink, setStatus, addButton, removeButton, removeAllButtons, removeFromList};
    };
    window.addEventListener("load", () => {
        timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
        uploadInfoDiv = document.getElementById("uploadinfodiv");
        uploadInfoDiv.style.display = "none";
        let topDiv = document.getElementById("topdiv");
        topDiv.addEventListener("dragover", (e) => {
            e.preventDefault();
        });
        topDiv.addEventListener("drop", (e) => {
            e.preventDefault();
            for (let i = 0; i < e.dataTransfer.files.length; i++) {
                let file = e.dataTransfer.files[i];
                uploadFile(file);
            }
        });

        let fileField = document.getElementById("filefield");
        fileField.addEventListener("change", (e) => {
            for (let i = 0; i < fileField.files.length; i++) {
                uploadFile(fileField.files[i]);
            }
            fileField.value = "";
        });

        let form = document.getElementById("uploadform");
        form.addEventListener("submit", (e) => {
            e.preventDefault();
        });
        form.style.display = "none";
        form.parentNode.insertBefore(document.createTextNode("Drag and drop files here to upload them!"), form);
        form.parentNode.insertBefore(document.createElement("BR"), form);
        let uploadButton = document.createElement("INPUT");
        uploadButton.type = "button";
        uploadButton.value = "Click to select a file";
        uploadButton.addEventListener("click", () => {
            fileField.click();
        });
        form.parentNode.insertBefore(uploadButton, form);

        fetchPreviousUploads();
    });
})();
