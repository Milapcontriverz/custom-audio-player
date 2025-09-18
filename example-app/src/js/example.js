import { audioplayer } from 'audio-player';

window.testEcho = () => {
    const inputValue = document.getElementById("echoInput").value;
    audioplayer.echo({ value: inputValue })
}
